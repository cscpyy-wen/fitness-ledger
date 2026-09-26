[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

$repoRoot = Get-ReleaseRepositoryRoot
$policy = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot 'release-policy.properties') -Encoding UTF8) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
    $separator = $line.IndexOf('=')
    $policy[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$helper = Join-Path $PSScriptRoot 'ReleaseProvenanceSignature.java'
$testDirectory = Join-Path $repoRoot "build\provenance-signature-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $testDirectory | Out-Null
$manifest = Join-Path $testDirectory 'manifest.json'
$signature = Join-Path $testDirectory 'manifest.sig'
$certificate = Join-Path $testDirectory 'certificate.der'
[IO.File]::WriteAllText($manifest, "{`"test`":true}`n", [Text.UTF8Encoding]::new($false))
$payload = $null
$storeEnv = 'FITNESS_APKSIGNER_STORE_PASSWORD_TEMP'
$keyEnv = 'FITNESS_APKSIGNER_KEY_PASSWORD_TEMP'
try {
    $payload = Unprotect-ReleaseSigningPayload (Join-Path $repoRoot '.signing\release-signing.dpapi.json')
    [Environment]::SetEnvironmentVariable($storeEnv, [string] $payload.storePassword, 'Process')
    [Environment]::SetEnvironmentVariable($keyEnv, [string] $payload.keyPassword, 'Process')
    & $java $helper sign `
        (Join-Path (Join-Path $repoRoot '.signing') ([string] $payload.storeFile)) `
        ([string] $payload.keyAlias) $manifest $signature $certificate | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Test detached provenance signing failed.' }
} finally {
    Remove-Item "Env:$storeEnv" -Force -ErrorAction SilentlyContinue
    Remove-Item "Env:$keyEnv" -Force -ErrorAction SilentlyContinue
    $payload = $null
}

try {
    & $java $helper verify $manifest $signature $certificate ([string] $policy.signingCertificateSha256) | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Test detached provenance verification failed.' }
    [IO.File]::AppendAllText($manifest, "tampered`n", [Text.UTF8Encoding]::new($false))
    $negativeOutput = @(& $java $helper verify $manifest $signature $certificate ([string] $policy.signingCertificateSha256) 2>&1)
    if ($LASTEXITCODE -eq 0) { throw 'Tampered provenance manifest unexpectedly verified.' }
} finally {
    Remove-Item -LiteralPath $testDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'PASS: APK-key detached provenance signature verifies and tampering fails closed.'
exit 0
