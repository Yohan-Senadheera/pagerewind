<#
.SYNOPSIS
    Creates the Kubernetes Secret "pagerewind-secrets" for the reference deployment.

.DESCRIPTION
    Passwords and tokens are generated randomly. The Telegram bot token is asked for without echoing
    it. Nothing is printed or written to disk outside a temporary file that is deleted immediately.
    Run it once before the pagerewind Argo CD application syncs.

    -TelegramToken  Update only the Telegram bot token, read from the clipboard (copy it first). Use this
                    to fix or rotate the token; the database passwords stay the same.
    -Replace        Recreate every value. The PostgreSQL and RabbitMQ data keep their old passwords, so
                    only use this on a fresh cluster (or delete their volumes too).
#>
param([switch]$Replace, [switch]$TelegramToken)

$ErrorActionPreference = 'Stop'
$namespace = 'pagerewind'
$name = 'pagerewind-secrets'

function New-RandomValue {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLower()
}

if ($TelegramToken) {
    $value = (Get-Clipboard | Out-String).Trim()
    if ($value -notmatch '^\d+:[\w-]{30,}$') { throw 'The clipboard does not hold a Telegram bot token (123456789:ABC...).' }
    $patch = @{ data = @{ 'telegram-bot-token' = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)) } } | ConvertTo-Json -Compress
    $file = [IO.Path]::GetTempFileName()
    try {
        Set-Content -Encoding ascii -Path $file -Value $patch
        kubectl -n $namespace patch secret $name --patch-file $file | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Updating the secret failed' }
    } finally {
        Remove-Item $file -Force
        $value = $null
        Set-Clipboard -Value ' '
    }
    kubectl -n $namespace rollout restart deployment/notify | Out-Null
    Write-Host 'Telegram token updated, clipboard cleared, notify restarting.'
    return
}

# --ignore-not-found prints nothing instead of an error, which Windows PowerShell 5.1 would treat as fatal.
if (-not (kubectl get namespace $namespace --ignore-not-found -o name)) {
    kubectl create namespace $namespace | Out-Null
}
if (kubectl -n $namespace get secret $name --ignore-not-found -o name) {
    if (-not $Replace) { Write-Host "Secret $name already exists. Use -TelegramToken to change only the Telegram token."; return }
    $answer = Read-Host 'This changes the database passwords; existing PostgreSQL and RabbitMQ data will reject them. Type REPLACE to continue'
    if ($answer -ne 'REPLACE') { Write-Host 'Cancelled.'; return }
    kubectl -n $namespace delete secret $name | Out-Null
}

# Read from the clipboard: pasting into a hidden prompt in Windows PowerShell can keep only one character.
Read-Host 'Copy your Telegram bot token from @BotFather to the clipboard, then press Enter (or just Enter to skip Telegram)' | Out-Null
$token = (Get-Clipboard | Out-String).Trim()
if ($token -and $token -notmatch '^\d+:[\w-]{30,}$') { throw 'The clipboard does not hold a Telegram bot token (123456789:ABC...).' }
Set-Clipboard -Value ' '

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
