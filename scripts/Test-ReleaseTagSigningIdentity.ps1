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
$testRepo = Join-Path $repoRoot "build\tag-signature-test-$([Guid]::NewGuid().ToString('N'))"
$temporaryKey = Join-Path $repoRoot ".signing\tag-test-$([Guid]::NewGuid().ToString('N'))"
$privateBytes = $null
try {
    $privateBytes = Unprotect-ReleaseTagSigningPrivateKey (
        Join-Path $repoRoot '.signing\release-tag-signing-key.dpapi.json'
    )
    [IO.File]::WriteAllBytes($temporaryKey, $privateBytes)
    & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary tag test key.' }

    New-Item -ItemType Directory -Path $testRepo | Out-Null
    & git -C $testRepo init -q
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize isolated tag-signature test repository.' }
    [IO.File]::WriteAllText((Join-Path $testRepo 'payload.txt'), "release tag signature test`n", [Text.UTF8Encoding]::new($false))
    & git -C $testRepo add payload.txt
    & git -C $testRepo -c user.name='Fitness Ledger Release Test' -c user.email='release-test@invalid' commit -q -m test
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated tag-signature test commit.' }
    & git -C $testRepo `
        -c gpg.format=ssh `
        -c "user.signingKey=$temporaryKey" `
        tag -s -m 'isolated signature self-test' v0-test
    if ($LASTEXITCODE -ne 0) { throw 'Could not sign the isolated test tag.' }
    Copy-Item -LiteralPath (Join-Path $repoRoot 'release-tag-allowed-signers') -Destination $testRepo
    $fingerprintLine = Get-Content -LiteralPath (Join-Path $repoRoot 'release-policy.properties') -Encoding UTF8 |
        Where-Object { $_ -match '^gitTagSigningSshKeyFingerprint=' } | Select-Object -First 1
    if ($null -eq $fingerprintLine) { throw 'Release tag signing fingerprint policy is missing.' }
    $fingerprint = $fingerprintLine.Substring($fingerprintLine.IndexOf('=') + 1)
    [void](Assert-ReleaseTagSignature $testRepo 'v0-test' 'fitness-ledger-release' $fingerprint)
} finally {
    if ($null -ne $privateBytes) { [Array]::Clear($privateBytes, 0, $privateBytes.Length) }
    if (Test-Path -LiteralPath $temporaryKey -PathType Leaf) { Remove-Item -LiteralPath $temporaryKey -Force }
    if (Test-Path -LiteralPath $testRepo -PathType Container) { Remove-Item -LiteralPath $testRepo -Recurse -Force }
}

Write-Host 'PASS: DPAPI-protected Ed25519 identity creates a Git tag verified by the pinned allowed-signers root.'
exit 0
