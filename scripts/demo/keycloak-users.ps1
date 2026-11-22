<#
.SYNOPSIS
    Creates a person who can sign in to https://app.pagerewind.dev, with one PageRewind role.

.DESCRIPTION
    Uses Keycloak's admin tool inside the Keycloak pod, with the admin login the operator created. The new
    person gets a random temporary password, shown once in this window; Keycloak asks them to choose their
    own at the first sign-in. Do not paste the temporary password anywhere.

.EXAMPLE
    ./scripts/demo/keycloak-users.ps1 -Username yohan -Role admin -FirstName Yohan -LastName Senadheera
.EXAMPLE
    ./scripts/demo/keycloak-users.ps1 -Username viewer-demo -Role viewer -FirstName Demo -LastName Viewer
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9][a-z0-9._-]{1,40}$')][string]$Username,
    [Parameter(Mandatory = $true)][ValidateSet('admin', 'responder', 'viewer')][string]$Role,
    [Parameter(Mandatory = $true)][string]$FirstName,
    [Parameter(Mandatory = $true)][string]$LastName
)

$ErrorActionPreference = 'Stop'
$namespace = 'keycloak'
$pod = 'keycloak-0'

function Get-AdminValue([string]$Key) {
    $encoded = kubectl -n $namespace get secret keycloak-initial-admin -o "jsonpath={.data.$Key}"
    if ($LASTEXITCODE -ne 0 -or -not $encoded) { throw "Keycloak admin secret value $Key not found" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
}

function New-TemporaryPassword {
    $bytes = New-Object byte[] 12
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
}

$password = New-TemporaryPassword
# Runs inside the pod. The values are passed as environment variables and never printed.
$script = @'
set -e
kc=/opt/keycloak/bin/kcadm.sh
$kc config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" > /dev/null 2>&1
if [ -z "$($kc get users -r pagerewind -q username="$NEW_USER" -q exact=true --fields id --format csv --noquotes)" ]; then
  $kc create users -r pagerewind -s username="$NEW_USER" -s enabled=true -s firstName="$NEW_FIRST" -s lastName="$NEW_LAST" > /dev/null 2>&1
  echo created
else
  echo exists
fi
$kc set-password -r pagerewind --username "$NEW_USER" --new-password "$NEW_PASSWORD" --temporary
$kc add-roles -r pagerewind --uusername "$NEW_USER" --rolename "$NEW_ROLE"
'@ -replace "`r", ''

$envArgs = @(
    "KC_ADMIN=$(Get-AdminValue 'username')"
    "KC_ADMIN_PASSWORD=$(Get-AdminValue 'password')"
    "NEW_USER=$Username"; "NEW_FIRST=$FirstName"; "NEW_LAST=$LastName"
    "NEW_PASSWORD=$password"; "NEW_ROLE=$Role"
)
$result = $script | kubectl -n $namespace exec -i $pod -- env @envArgs bash -s
if ($LASTEXITCODE -ne 0) { throw "Creating $Username failed: $result" }
$envArgs = $null

Write-Host ""
Write-Host "$Username ($Role): $(if ($result -match 'exists') { 'already existed; password reset' } else { 'created' })"
Write-Host "Temporary password (shown once; Keycloak asks for a new one at the first sign-in):"
Write-Host "    $password" -ForegroundColor Yellow
Write-Host "Sign in at https://app.pagerewind.dev"
$password = $null
