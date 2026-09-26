[CmdletBinding()]
param(
    [string] $KeystorePath = '',
    [string] $KeyAlias = '',
    [Security.SecureString] $StorePassword,
    [Security.SecureString] $KeyPassword,
    [string] $LegacyPropertiesPath = '',
    [switch] $RemoveLegacyProperties
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

function Read-LegacySigningProperties([string] $Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match '^\s*[#!]' -or [string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw 'Malformed legacy signing properties file.' }
        $name = $line.Substring(0, $separator).Trim()
        if ($values.ContainsKey($name)) { throw "Duplicate legacy signing property: $name" }
        $values[$name] = $line.Substring($separator + 1)
    }
    foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string] $values[$name])) {
            throw "Legacy signing property '$name' is missing."
        }
    }
    return $values
}

$repoRoot = Get-ReleaseRepositoryRoot
$signingDirectory = Join-Path $repoRoot '.signing'
$blobPath = Join-Path $signingDirectory 'release-signing.dpapi.json'
$legacyPath = $null
$storePasswordPlain = $null
$keyPasswordPlain = $null

try {
    if (-not [string]::IsNullOrWhiteSpace($LegacyPropertiesPath)) {
        $legacyPath = [IO.Path]::GetFullPath($LegacyPropertiesPath)
        $expectedLegacyParent = [IO.Path]::GetFullPath($signingDirectory).TrimEnd('\')
        $actualLegacyParent = [IO.Path]::GetFullPath((Split-Path -Parent $legacyPath)).TrimEnd('\')
        if (-not $actualLegacyParent.Equals($expectedLegacyParent, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Legacy signing properties must be inside the repository .signing directory.'
        }
        $legacy = Read-LegacySigningProperties $legacyPath
        $KeystorePath = Join-Path $signingDirectory ([string] $legacy['storeFile'])
        $KeyAlias = [string] $legacy['keyAlias']
        $storePasswordPlain = [string] $legacy['storePassword']
        $keyPasswordPlain = [string] $legacy['keyPassword']
    } else {
        if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
            $KeystorePath = Join-Path $signingDirectory 'fitness-ledger-personal.jks'
        }
        if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
            $KeyAlias = Read-Host 'Release key alias'
        }
        if ($null -eq $StorePassword) {
            $StorePassword = Read-Host 'Release keystore password' -AsSecureString
        }
        if ($null -eq $KeyPassword) {
            $KeyPassword = Read-Host 'Release key password' -AsSecureString
        }
        $storePasswordPlain = ConvertTo-UnsecuredString $StorePassword
        $keyPasswordPlain = ConvertTo-UnsecuredString $KeyPassword
    }

    $resolvedKeystore = [IO.Path]::GetFullPath($KeystorePath)
    $resolvedSigningDirectory = [IO.Path]::GetFullPath($signingDirectory).TrimEnd('\')
    $keystoreParent = [IO.Path]::GetFullPath((Split-Path -Parent $resolvedKeystore)).TrimEnd('\')
    if (-not $keystoreParent.Equals($resolvedSigningDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The locally protected keystore must be directly inside .signing.'
    }
    if (-not (Test-Path -LiteralPath $resolvedKeystore -PathType Leaf)) {
        throw "Release keystore is missing: $resolvedKeystore"
    }
    if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'Release key alias is empty.' }

    $payload = @{
        storeFile = [IO.Path]::GetFileName($resolvedKeystore)
        storePassword = $storePasswordPlain
        keyAlias = $KeyAlias
        keyPassword = $keyPasswordPlain
    }
    Protect-ReleaseSigningPayload $payload $blobPath

    $roundTrip = Unprotect-ReleaseSigningPayload $blobPath
    if ($roundTrip.storeFile -cne $payload.storeFile -or
        $roundTrip.storePassword -cne $payload.storePassword -or
        $roundTrip.keyAlias -cne $payload.keyAlias -or
        $roundTrip.keyPassword -cne $payload.keyPassword) {
        throw 'DPAPI signing-secret round-trip verification failed.'
    }

    & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if ($RemoveLegacyProperties) {
        if ($null -eq $legacyPath) {
            throw '-RemoveLegacyProperties requires -LegacyPropertiesPath.'
        }
        [IO.File]::Delete($legacyPath)
        if (Test-Path -LiteralPath $legacyPath) {
            throw 'Legacy plaintext signing properties could not be removed.'
        }
        & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1')
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    Write-Host 'PASS: release signing credentials are protected with Windows DPAPI CurrentUser scope.'
    Write-Host "  blob=$blobPath"
    Write-Host "  legacyPlaintextRemoved=$([bool] $RemoveLegacyProperties)"
    if ($RemoveLegacyProperties) {
        Write-Warning 'Deletion cannot revoke copies or forensic remnants of the formerly readable key/password files.'
    }
} finally {
    $storePasswordPlain = $null
    $keyPasswordPlain = $null
    $StorePassword = $null
    $KeyPassword = $null
}
exit 0
