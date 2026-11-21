<#
.SYNOPSIS
    Creates the Keycloak client secrets for the reference deployment.

.DESCRIPTION
    Generates random secrets for the three clients of the pagerewind realm and stores them in two places,
    never on disk and never printed:
      - namespace keycloak, Secret "keycloak-clients": read by the KeycloakRealmImport placeholders.
      - namespace pagerewind, Secret "pagerewind-secrets": new keys for the gateway, notify and scripts
        (web-client-secret, notify-client-secret, automation-client-secret). Existing keys stay.
    Run it once before the realm import syncs. If "keycloak-clients" already exists, nothing changes.
#>
param([string]$AppUrl = 'https://app.pagerewind.dev')

$ErrorActionPreference = 'Stop'

function New-RandomValue {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLower()
}

function ConvertTo-Base64([string]$Value) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value)) }

# --ignore-not-found prints nothing instead of an error, which Windows PowerShell 5.1 would treat as fatal.
if (-not (kubectl get namespace keycloak --ignore-not-found -o name)) { throw 'Namespace keycloak does not exist yet.' }
if (-not (kubectl -n pagerewind get secret pagerewind-secrets --ignore-not-found -o name)) {
    throw 'Secret pagerewind-secrets does not exist. Run scripts/demo/new-secrets.ps1 first.'
}
if (kubectl -n keycloak get secret keycloak-clients --ignore-not-found -o name) {
    Write-Host 'Secret keycloak-clients already exists; nothing changed.'
    return
}

$web = New-RandomValue
$notify = New-RandomValue
$automation = New-RandomValue

$clients = [IO.Path]::GetTempFileName()
$patch = [IO.Path]::GetTempFileName()
try {
    @(
        "PAGEREWIND_URL=$AppUrl"
        "PAGEREWIND_WEB_CLIENT_SECRET=$web"
        "NOTIFY_CLIENT_SECRET=$notify"
        "AUTOMATION_CLIENT_SECRET=$automation"
    ) | Set-Content -Encoding ascii -Path $clients
    kubectl -n keycloak create secret generic keycloak-clients --from-env-file=$clients | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Creating secret keycloak-clients failed' }

    @{ data = @{
            'web-client-secret'        = ConvertTo-Base64 $web
            'notify-client-secret'     = ConvertTo-Base64 $notify
            'automation-client-secret' = ConvertTo-Base64 $automation
        } } | ConvertTo-Json -Compress | Set-Content -Encoding ascii -Path $patch
    kubectl -n pagerewind patch secret pagerewind-secrets --patch-file $patch | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Adding the client secrets to pagerewind-secrets failed' }
} finally {
    Remove-Item $clients, $patch -Force
    $web = $null; $notify = $null; $automation = $null
}
Write-Host "Created keycloak/keycloak-clients (app URL $AppUrl) and added the client secrets to pagerewind/pagerewind-secrets."
