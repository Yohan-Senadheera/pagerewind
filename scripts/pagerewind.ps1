<#
.SYNOPSIS
    Build, tear down, stop and start the PageRewind reference deployment on AWS.

.DESCRIPTION
    up      Create the cluster from nothing: Elastic IP, network, nodes, k3s, Argo CD, then everything
            in platform/apps. Finishes when https://whoami.<domain> answers with a valid certificate.
    down    Destroy the cluster and network. Keeps the Terraform state bucket and the Elastic IP
            (so DNS stays valid). Add -ReleaseIp to release the IP as well.
    stop    Stop both nodes (disks and IP stay; almost no cost).
    start   Start both nodes again.
    status  Show nodes and Argo CD applications.
    kubeconfig  Fetch the cluster admin kubeconfig again.

    Needs: aws (logged in with 'aws login --profile pager'), terraform, kubectl, helm, ssh,
    infra/backend.hcl and the SSH key ~/.ssh/pagerewind.

.EXAMPLE
    ./scripts/pagerewind.ps1 up
.EXAMPLE
    ./scripts/pagerewind.ps1 down
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('up', 'down', 'stop', 'start', 'status', 'kubeconfig')]
    [string]$Command,

    [switch]$ReleaseIp,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root 'infra'
$AwsProfile = 'pager'
$Domain = 'pagerewind.dev'
$ArgoChartVersion = '10.18.0'
$Kubeconfig = Join-Path $HOME '.kube\pagerewind'
$SshKey = Join-Path $HOME '.ssh\pagerewind'
$KnownHosts = Join-Path $HOME '.ssh\known_hosts_pagerewind'

function Assert-Success([string]$What) {
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

# Runs a native command and reports success. Its stderr is discarded; in Windows PowerShell 5.1 any
# stderr line would otherwise become a terminating error under $ErrorActionPreference = 'Stop'.
function Test-NativeCommand([scriptblock]$Command) {
    $ErrorActionPreference = 'Continue'
    & $Command 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "==> $Text" -ForegroundColor Cyan
}

function Assert-Prerequisites {
    foreach ($tool in 'aws', 'terraform', 'kubectl', 'helm', 'ssh') {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is not installed" }
    }
    if (-not (Test-Path (Join-Path $Infra 'backend.hcl'))) {
        throw "infra/backend.hcl is missing. Copy infra/backend.hcl.example and fill it in."
    }
    if (-not (Test-Path "$SshKey.pub")) {
        throw "SSH key $SshKey.pub is missing. Create it with: ssh-keygen -t ed25519 -f `"$SshKey`""
    }
    if (-not (Test-NativeCommand { aws sts get-caller-identity --profile $AwsProfile })) {
        throw "AWS login has expired. Run: aws login --profile $AwsProfile"
    }
}

function Invoke-Terraform([string]$Stack, [string[]]$Arguments) {
    terraform "-chdir=$(Join-Path $Infra $Stack)" @Arguments
    Assert-Success "terraform $($Arguments[0]) ($Stack)"
}

function Initialize-Stack([string]$Stack) {
    Invoke-Terraform $Stack @('init', '-input=false', '-backend-config=../backend.hcl')
}

function Get-AdminCidr {
    $ip = (Invoke-RestMethod -Uri 'https://checkip.amazonaws.com' -UseBasicParsing).ToString().Trim()
    return "$ip/32"
}

function Get-ServerIp {
    $ip = terraform "-chdir=$(Join-Path $Infra 'ip')" output -raw public_ip
    Assert-Success 'reading the Elastic IP'
    return $ip.Trim()
}

function Get-InstanceIds {
    $ids = aws ec2 describe-instances --profile $AwsProfile `
        --filters 'Name=tag:Project,Values=pagerewind' 'Name=instance-state-name,Values=pending,running,stopping,stopped' `
        --query 'Reservations[].Instances[].InstanceId' --output text
    Assert-Success 'listing instances'
    return @($ids -split '\s+' | Where-Object { $_ })
}

function Invoke-Ssh([string]$Ip, [string]$RemoteCommand) {
    $ErrorActionPreference = 'Continue'
    ssh -i $SshKey -o StrictHostKeyChecking=accept-new -o "UserKnownHostsFile=$KnownHosts" `
        -o ConnectTimeout=5 -o BatchMode=yes "ubuntu@$Ip" $RemoteCommand 2>$null
}

function Wait-ForCluster([string]$Ip) {
    Write-Step "Waiting for k3s on both nodes (first boot takes 2 to 4 minutes)"
    for ($i = 1; $i -le 60; $i++) {
        $nodes = Invoke-Ssh $Ip 'sudo k3s kubectl get nodes --no-headers'
        $ready = @($nodes | Where-Object { $_ -match '\sReady\s' }).Count
        if ($ready -ge 2) { Write-Host "Both nodes are Ready."; return }
        Start-Sleep -Seconds 10
    }
    throw "k3s did not become ready in 10 minutes. Check with: ssh -i $SshKey ubuntu@$Ip"
}

function Save-Kubeconfig([string]$Ip) {
    Write-Step "Saving the cluster admin kubeconfig to $Kubeconfig"
    New-Item -ItemType Directory -Force (Split-Path $Kubeconfig) | Out-Null
    $config = Invoke-Ssh $Ip 'sudo cat /etc/rancher/k3s/k3s.yaml'
    if (-not $config) { throw 'Could not read the kubeconfig from the server' }
    # Never print this file: it contains the cluster admin key.
    $config -replace '127\.0\.0\.1', $Ip | Set-Content -Encoding ascii -Path $Kubeconfig
    $env:KUBECONFIG = $Kubeconfig
}

function Install-ArgoCd {
    Write-Step "Installing Argo CD (chart $ArgoChartVersion) and the root application"
    if (-not (Test-NativeCommand { helm repo add argo https://argoproj.github.io/argo-helm --force-update })) {
        throw 'helm repo add failed'
    }
    helm upgrade --install argocd argo/argo-cd --version $ArgoChartVersion --namespace argocd --create-namespace `
        --values (Join-Path $Root 'platform\argocd-values.yaml') --wait --timeout 10m
    Assert-Success 'helm install argocd'
    kubectl apply -f (Join-Path $Root 'platform\root-app.yaml')
    Assert-Success 'kubectl apply root-app'
}

function Wait-ForTestPage {
    $url = "https://whoami.$Domain/"
    Write-Step "Waiting for $url to answer with a valid certificate"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    for ($i = 1; $i -le 60; $i++) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 10
            if ($response.StatusCode -eq 200) { Write-Host "$url is up with a trusted certificate."; return }
        } catch {
            Start-Sleep -Seconds 10
        }
    }
    throw "$url did not come up in 10 minutes. Check: kubectl get applications -n argocd; kubectl -n whoami get certificate"
}

switch ($Command) {
    'up' {
        Assert-Prerequisites
        $cidr = Get-AdminCidr
        Write-Step "Elastic IP (infra/ip)"
        Initialize-Stack 'ip'
        Invoke-Terraform 'ip' @('apply', '-input=false', '-auto-approve')
        Write-Step "Network and nodes (infra/reference), admin access from $cidr"
        Initialize-Stack 'reference'
        Invoke-Terraform 'reference' @('apply', '-input=false', '-auto-approve', "-var=admin_cidr=$cidr")
        $ip = Get-ServerIp
        if (Test-Path $KnownHosts) { Remove-Item $KnownHosts }
        Wait-ForCluster $ip
        Save-Kubeconfig $ip
        Install-ArgoCd
        Wait-ForTestPage
        Write-Step 'Done'
        Write-Host "Use the cluster:   `$env:KUBECONFIG = `"$Kubeconfig`""
        Write-Host 'Argo CD UI:        kubectl port-forward svc/argocd-server -n argocd 8080:80  (then http://localhost:8080)'
        Write-Host 'Argo CD password:  read secret argocd-initial-admin-secret in namespace argocd, change it, then delete the secret'
    }
    'down' {
        Assert-Prerequisites
        if (-not $Force) {
            $answer = Read-Host 'This destroys the cluster, its data and the network. Type DESTROY to continue'
            if ($answer -ne 'DESTROY') { Write-Host 'Cancelled.'; return }
        }
        Write-Step 'Destroying network and nodes (infra/reference)'
        Initialize-Stack 'reference'
        Invoke-Terraform 'reference' @('destroy', '-input=false', '-auto-approve', "-var=admin_cidr=$(Get-AdminCidr)")
        if ($ReleaseIp) {
            Write-Step 'Releasing the Elastic IP (infra/ip); DNS records must be updated after the next up'
            Initialize-Stack 'ip'
            Invoke-Terraform 'ip' @('destroy', '-input=false', '-auto-approve')
        } else {
            Write-Host 'Kept the Elastic IP so DNS stays valid. Use -ReleaseIp to release it too.'
        }
        foreach ($file in $Kubeconfig, $KnownHosts) { if (Test-Path $file) { Remove-Item $file } }
        Write-Step 'Done. The Terraform state bucket (infra/bootstrap) is never destroyed by this script.'
    }
    'stop' {
        Assert-Prerequisites
        $ids = Get-InstanceIds
        if ($ids.Count -eq 0) { Write-Host 'No nodes found.'; return }
        aws ec2 stop-instances --profile $AwsProfile --instance-ids @ids --output text | Out-Null
        Assert-Success 'stop-instances'
        Write-Host "Stopping $($ids -join ', ')."
    }
    'start' {
        Assert-Prerequisites
        $ids = Get-InstanceIds
        if ($ids.Count -eq 0) { Write-Host 'No nodes found. Run: ./scripts/pagerewind.ps1 up'; return }
        aws ec2 start-instances --profile $AwsProfile --instance-ids @ids --output text | Out-Null
        Assert-Success 'start-instances'
        aws ec2 wait instance-running --profile $AwsProfile --instance-ids @ids
        Assert-Success 'waiting for instances'
        Wait-ForCluster (Get-ServerIp)
    }
    'status' {
        Assert-Prerequisites
        aws ec2 describe-instances --profile $AwsProfile --filters 'Name=tag:Project,Values=pagerewind' `
            --query "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value,State.Name,InstanceType]" --output table
        if (Test-Path $Kubeconfig) {
            $env:KUBECONFIG = $Kubeconfig
            kubectl get nodes
            kubectl get applications -n argocd
        }
    }
    'kubeconfig' {
        Assert-Prerequisites
        Save-Kubeconfig (Get-ServerIp)
        Write-Host "Saved. Run: `$env:KUBECONFIG = `"$Kubeconfig`""
    }
}
