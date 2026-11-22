<#
.SYNOPSIS
    Copies the in-cluster secrets to AWS Secrets Manager, where External Secrets reads them from.

.DESCRIPTION
    Reads the Kubernetes Secrets "pagerewind-secrets" (namespace pagerewind) and "keycloak-clients"
    (namespace keycloak) and stores each as one JSON secret in AWS Secrets Manager:
        pagerewind/app               every key of pagerewind-secrets
        pagerewind/keycloak-clients  every key of keycloak-clients
    The nodes' IAM role may read only secrets named pagerewind/*. Values are never printed; the temporary
    JSON file is deleted right away. Running it again stores the current values as a new version.

    Cost: AWS Secrets Manager charges about 0.40 USD per secret per month.
#>
$ErrorActionPreference = 'Stop'
$AwsProfile = 'pager'

function Get-SecretData([string]$Namespace, [string]$Name) {
    $json = kubectl -n $Namespace get secret $Name -o json
    if ($LASTEXITCODE -ne 0) { throw "Secret $Namespace/$Name not found" }
    $data = ($json | ConvertFrom-Json).data
    $values = [ordered]@{}
    foreach ($key in ($data.PSObject.Properties.Name | Sort-Object)) {
        $values[$key] = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($data.$key))
    }
    return $values
}

function Save-AwsSecret([string]$Name, $Values) {
    $file = [IO.Path]::GetTempFileName()
    try {
        # UTF-8 without a byte-order mark: the AWS CLI would otherwise store the mark as part of the JSON.
        [IO.File]::WriteAllText($file, ($Values | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding $false))
        $exists = $false
        $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        aws secretsmanager describe-secret --profile $AwsProfile --secret-id $Name 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $exists = $true }
        $ErrorActionPreference = $previous
        if ($exists) {
            aws secretsmanager put-secret-value --profile $AwsProfile --secret-id $Name --secret-string "file://$file" | Out-Null
        } else {
            aws secretsmanager create-secret --profile $AwsProfile --name $Name `
                --description 'PageRewind reference deployment (read by External Secrets)' `
                --secret-string "file://$file" | Out-Null
        }
        if ($LASTEXITCODE -ne 0) { throw "Storing $Name failed" }
        Write-Host "Stored $Name ($($Values.Count) keys, values not shown)."
    } finally {
        Remove-Item $file -Force
    }
}

Save-AwsSecret 'pagerewind/app' (Get-SecretData 'pagerewind' 'pagerewind-secrets')
Save-AwsSecret 'pagerewind/keycloak-clients' (Get-SecretData 'keycloak' 'keycloak-clients')
