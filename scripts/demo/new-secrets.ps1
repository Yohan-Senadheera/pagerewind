<#
.SYNOPSIS
    Creates the Kubernetes Secret "pagerewind-secrets" for the reference deployment.

.DESCRIPTION
    Passwords and tokens are generated randomly. The Telegram bot token is asked for without echoing
    it. Nothing is printed or written to disk outside a temporary file that is deleted immediately.
    Run it once before the pagerewind Argo CD application syncs. Use -Replace to rotate everything.
#>
param([switch]$Replace)

$ErrorActionPreference = 'Stop'
$namespace = 'pagerewind'
$name = 'pagerewind-secrets'

function New-RandomValue {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLower()
}

# --ignore-not-found prints nothing instead of an error, which Windows PowerShell 5.1 would treat as fatal.
if (-not (kubectl get namespace $namespace --ignore-not-found -o name)) {
    kubectl create namespace $namespace | Out-Null
}
if (kubectl -n $namespace get secret $name --ignore-not-found -o name) {
    if (-not $Replace) { Write-Host "Secret $name already exists. Use -Replace to rotate it."; return }
    kubectl -n $namespace delete secret $name | Out-Null
}

$secure = Read-Host 'Telegram bot token from @BotFather (input hidden; leave empty to skip Telegram)' -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

$file = [IO.Path]::GetTempFileName()
try {
    @(
        "postgres-password=$(New-RandomValue)"
        "rabbitmq-password=$(New-RandomValue)"
        "ingest-token=$(New-RandomValue)"
        "action-link-secret=$(New-RandomValue)"
        "telegram-webhook-secret=$(New-RandomValue)"
        "telegram-bot-token=$token"
    ) | Set-Content -Encoding ascii -Path $file
    kubectl -n $namespace create secret generic $name --from-env-file=$file | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Creating the secret failed' }
} finally {
    Remove-Item $file -Force
    $token = $null
}
Write-Host "Created secret $name in namespace $namespace."
