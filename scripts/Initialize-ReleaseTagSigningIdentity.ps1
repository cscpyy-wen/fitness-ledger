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
$signingDirectory = Join-Path $repoRoot '.signing'
$protectedKeyPath = Join-Path $signingDirectory 'release-tag-signing-key.dpapi.json'
$allowedSignersPath = Join-Path $repoRoot 'release-tag-allowed-signers'
if (Test-Path -LiteralPath $protectedKeyPath) {
    throw "Refusing to replace the existing protected release tag identity: $protectedKeyPath"
}
if (Test-Path -LiteralPath $allowedSignersPath) {
    throw "Refusing to replace the repository release tag trust root: $allowedSignersPath"
}

if (-not (Test-Path -LiteralPath $signingDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $signingDirectory | Out-Null
}
& (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1') | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not establish the restricted .signing ACL.' }

$sshKeygen = (Get-Command ssh-keygen -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$temporaryPrivateKey = Join-Path $signingDirectory "tag-key-init-$([Guid]::NewGuid().ToString('N'))"
$temporaryPublicKey = "$temporaryPrivateKey.pub"
$privateBytes = $null
try {
    & $sshKeygen -q -t ed25519 -N '' -C 'fitness-ledger-release' -f $temporaryPrivateKey
    if ($LASTEXITCODE -ne 0 -or
        -not (Test-Path -LiteralPath $temporaryPrivateKey -PathType Leaf) -or
        -not (Test-Path -LiteralPath $temporaryPublicKey -PathType Leaf)) {
        throw 'ssh-keygen did not create the release tag signing identity.'
    }
    & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1') | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary release tag key ACL.' }

    $publicParts = (Get-Content -LiteralPath $temporaryPublicKey -Raw -Encoding UTF8).Trim() -split '\s+'
    if ($publicParts.Count -lt 2 -or $publicParts[0] -cne 'ssh-ed25519' -or
        $publicParts[1] -notmatch '^[A-Za-z0-9+/]+={0,2}$') {
        throw 'Generated release tag public key is malformed.'
    }
    $privateBytes = [IO.File]::ReadAllBytes($temporaryPrivateKey)
    Protect-ReleaseTagSigningPrivateKey $privateBytes $protectedKeyPath
    $allowedSigner = "fitness-ledger-release ssh-ed25519 $($publicParts[1])`n"
    [IO.File]::WriteAllText($allowedSignersPath, $allowedSigner, [Text.UTF8Encoding]::new($false))

    $fingerprintOutput = @(& $sshKeygen -lf $temporaryPublicKey -E sha256 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0 -or ($fingerprintOutput -join ' ') -notmatch '(SHA256:[A-Za-z0-9+/]+)') {
        throw 'Could not derive the release tag signing key fingerprint.'
    }
    $fingerprint = $Matches[1]
} finally {
    if ($null -ne $privateBytes) { [Array]::Clear($privateBytes, 0, $privateBytes.Length) }
    foreach ($path in @($temporaryPrivateKey, $temporaryPublicKey)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            Remove-Item -LiteralPath $path -Force
        }
    }
    if (Test-Path -LiteralPath $protectedKeyPath -PathType Leaf) {
        & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1') | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the final protected signing identity ACL.' }
    }
}

Write-Host 'PASS: generated a dedicated Ed25519 release-tag identity.'
Write-Host '  privateKeyAtRest=Windows DPAPI CurrentUser ciphertext under restricted .signing ACL'
Write-Host "  allowedSigners=$allowedSignersPath"
Write-Host "  fingerprint=$fingerprint"
Write-Host 'Add the exact fingerprint to release-policy.properties as gitTagSigningSshKeyFingerprint.'
exit 0
