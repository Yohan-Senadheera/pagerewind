<#
.SYNOPSIS
    Live demo helpers for the reference deployment.

.DESCRIPTION
    seed      Create the demo on-call setup: you are on call for "checkout"; if you do not acknowledge
              within 2 minutes, "backup" (also your Telegram chat) is paged. Asks for your Telegram chat id.
    webhook   Register https://notify.<domain>/v1/telegram/webhook with Telegram (secret token included).
    alert     Send a firing alert for "checkout" to https://ingest.<domain>.
    resolve   Send the matching "resolved" alert.
    status    Show the escalation jobs of the newest incident.

    Secrets are read from the cluster and never printed. The incident and schedule APIs need a Keycloak
    token; seed and status get one with the pagerewind-automation client (admin role).
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('seed', 'webhook', 'alert', 'resolve', 'status')]
    [string]$Command
)

$ErrorActionPreference = 'Stop'
$Domain = 'pagerewind.dev'
$Namespace = 'pagerewind'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-SecretValue([string]$Key) {
    $encoded = kubectl -n $Namespace get secret pagerewind-secrets -o "jsonpath={.data.$Key}"
    if ($LASTEXITCODE -ne 0 -or -not $encoded) { throw "Secret value $Key not found" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
}

function Get-ApiToken {
    $body = @{
        grant_type    = 'client_credentials'
        client_id     = 'pagerewind-automation'
        client_secret = (Get-SecretValue 'automation-client-secret')
    }
    $result = Invoke-RestMethod -Method Post -Body $body `
        -Uri "https://auth.$Domain/realms/pagerewind/protocol/openid-connect/token"
    return @{ Authorization = "Bearer $($result.access_token)" }
}

function Invoke-WithPortForward([string]$Service, [int]$LocalPort, [scriptblock]$Action) {
    $job = Start-Job -ScriptBlock {
        param($ns, $svc, $port, $kubeconfig)
        $env:KUBECONFIG = $kubeconfig
        kubectl -n $ns port-forward "svc/$svc" "${port}:80"
    } -ArgumentList $Namespace, $Service, $LocalPort, $env:KUBECONFIG
    try {
        for ($i = 0; $i -lt 30; $i++) {
            try { Invoke-RestMethod "http://localhost:$LocalPort/actuator/health/readiness" -TimeoutSec 2 | Out-Null; break }
            catch { Start-Sleep -Milliseconds 500 }
        }
        & $Action "http://localhost:$LocalPort"
    } finally {
        Stop-Job $job | Out-Null
        Remove-Job $job -Force | Out-Null
    }
}

function Send-Alert([string]$Status) {
    $body = @{
        version      = '4'
        status       = $Status
        receiver     = 'pagerewind'
        commonLabels = @{ alertname = 'HighErrorRate' }
        alerts       = @(@{
                status      = $Status
                labels      = @{ alertname = 'HighErrorRate'; service = 'checkout'; severity = 'critical' }
                annotations = @{ summary = 'Checkout 5xx rate above 5% for 5 minutes' }
                startsAt    = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
                fingerprint = 'live-demo-checkout-5xx'
            })
    } | ConvertTo-Json -Depth 6
    $headers = @{ Authorization = "Bearer $(Get-SecretValue 'ingest-token')" }
    $result = Invoke-RestMethod -Method Post -Uri "https://ingest.$Domain/v1/alerts/alertmanager" `
        -Headers $headers -ContentType 'application/json' -Body $body
    Write-Host "Accepted $($result.accepted) alert(s)."
}

switch ($Command) {
    'seed' {
        $chatId = Read-Host 'Your Telegram chat id (numbers only)'
        if ($chatId -notmatch '^-?\d+$') { throw 'The chat id must be a number' }
        $today = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
        $script:auth = Get-ApiToken
        Invoke-WithPortForward 'schedule' 18083 {
            param($base)
            $put = {
                param($path, $body)
                Invoke-RestMethod -Method Put -Uri "$base$path" -Headers $script:auth -ContentType 'application/json' -Body $body | Out-Null
                Write-Host "  PUT $path"
            }
            & $put '/v1/people/oncall' "{`"name`":`"On-call engineer`",`"contacts`":[{`"channel`":`"TELEGRAM`",`"address`":`"$chatId`"}]}"
            & $put '/v1/people/backup' "{`"name`":`"Backup engineer`",`"contacts`":[{`"channel`":`"TELEGRAM`",`"address`":`"$chatId`"}]}"
            & $put '/v1/teams/checkout-team' '{"name":"Checkout team","defaultTeam":true,"services":["checkout","payments"]}'
            & $put '/v1/teams/checkout-team/rotations/primary' "{`"name`":`"Primary`",`"members`":[`"oncall`"],`"start`":`"${today}T00:00:00`",`"timeZone`":`"UTC`",`"shiftHours`":24}"
            & $put '/v1/teams/checkout-team/escalation-policy' '{"repeat":0,"steps":[{"escalateAfterMinutes":2,"targetType":"ROTATION","targetId":"primary"},{"escalateAfterMinutes":2,"targetType":"PERSON","targetId":"backup"}]}'
        }
        Write-Host 'Demo team ready.'
    }
    'webhook' {
        $botToken = Get-SecretValue 'telegram-bot-token'
        $body = @{
            url             = "https://notify.$Domain/v1/telegram/webhook"
            secret_token    = (Get-SecretValue 'telegram-webhook-secret')
            allowed_updates = @('callback_query')
        } | ConvertTo-Json
        $result = Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$botToken/setWebhook" `
            -ContentType 'application/json' -Body $body
        $botToken = $null
        Write-Host "Telegram setWebhook: ok=$($result.ok) $($result.description)"
    }
    'alert' { Send-Alert 'firing' }
    'resolve' { Send-Alert 'resolved' }
    'status' {
        $script:auth = Get-ApiToken
        Invoke-WithPortForward 'incident' 18082 {
            param($base)
            $incident = (Invoke-RestMethod "$base/v1/incidents?limit=1" -Headers $script:auth)[0]
            if (-not $incident) { Write-Host 'No incidents yet.'; return }
            Write-Host "Incident $($incident.id): $($incident.status), $($incident.alertCount) alert(s)"
            $script:incidentId = $incident.id
        }
        if ($script:incidentId) {
            Invoke-WithPortForward 'schedule' 18083 {
                param($base)
                $view = Invoke-RestMethod "$base/v1/escalations/$($script:incidentId)" -Headers $script:auth
                $view.jobs | Select-Object stepNo, round, fireAt, status, personId | Format-Table -AutoSize
            }
        }
    }
}
