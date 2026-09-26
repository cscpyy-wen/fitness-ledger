[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v[0-9A-Za-z._-]+$')]
    [string] $Tag,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedRevision,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $Message
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

function Invoke-GitText([string[]] $Arguments) {
    $output = @(& git -C $script:RepoRoot @Arguments 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return @($output)
}

function Read-Policy([string] $Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed policy line: $line" }
        $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

$RepoRoot = Get-ReleaseRepositoryRoot
$head = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
if ($head -cne $ExpectedRevision) { throw "HEAD '$head' does not match '$ExpectedRevision'." }
if (@(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all')).Count -ne 0) {
    throw 'A signed release tag requires a clean worktree and index.'
}
if (@(Invoke-GitText @('tag', '--list', $Tag) | Where-Object { $_.Trim() }).Count -ne 0) {
    throw "Refusing to replace existing tag '$Tag'."
}

$policy = Read-Policy (Join-Path $RepoRoot 'release-policy.properties')
$expectedFingerprint = [string] $policy.gitTagSigningSshKeyFingerprint
if ($expectedFingerprint -notmatch '^SHA256:[A-Za-z0-9+/]+$') {
    throw 'release-policy.properties has no valid gitTagSigningSshKeyFingerprint.'
}
$allowedSigners = Join-Path $RepoRoot 'release-tag-allowed-signers'
$allowedLines = @(Get-Content -LiteralPath $allowedSigners -Encoding UTF8 | Where-Object { $_ -and $_ -notmatch '^#' })
if ($allowedLines.Count -ne 1 -or $allowedLines[0] -notmatch '^fitness-ledger-release ssh-ed25519 [A-Za-z0-9+/]+={0,2}$') {
    throw 'The release tag allowed-signers trust root must contain exactly one pinned Ed25519 key.'
}

$sshKeygen = (Get-Command ssh-keygen -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$publicProbe = Join-Path $RepoRoot ".signing\tag-public-probe-$([Guid]::NewGuid().ToString('N')).pub"
$temporaryPrivateKey = Join-Path $RepoRoot ".signing\tag-signing-$([Guid]::NewGuid().ToString('N'))"
$privateBytes = $null
$tagCreated = $false
try {
    [IO.File]::WriteAllText($publicProbe, (($allowedLines[0] -split '\s+', 2)[1]) + "`n", [Text.UTF8Encoding]::new($false))
    $fingerprintOutput = @(& $sshKeygen -lf $publicProbe -E sha256 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0 -or ($fingerprintOutput -join ' ') -notmatch '(SHA256:[A-Za-z0-9+/]+)') {
        throw 'Could not derive the pinned release tag key fingerprint.'
    }
    if ($Matches[1] -cne $expectedFingerprint) {
        throw "Release tag public key fingerprint '$($Matches[1])' does not match policy '$expectedFingerprint'."
    }

    $privateBytes = Unprotect-ReleaseTagSigningPrivateKey (
        Join-Path $RepoRoot '.signing\release-tag-signing-key.dpapi.json'
    )
    [IO.File]::WriteAllBytes($temporaryPrivateKey, $privateBytes)
    & (Join-Path $PSScriptRoot 'Set-ReleaseSigningAcl.ps1') | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary release tag key ACL.' }

    $derivedPublic = @(& $sshKeygen -y -f $temporaryPrivateKey 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0 -or $derivedPublic.Count -ne 1) { throw 'Could not derive public key from protected tag identity.' }
    $expectedPublic = ($allowedLines[0] -split '\s+', 2)[1]
    $derivedParts = $derivedPublic[0].Trim() -split '\s+'
    $expectedParts = $expectedPublic.Trim() -split '\s+'
    if ($derivedParts.Count -lt 2 -or $expectedParts.Count -ne 2 -or
        $derivedParts[0] -cne 'ssh-ed25519' -or
        $derivedParts[0] -cne $expectedParts[0] -or
        $derivedParts[1] -cne $expectedParts[1]) {
        throw 'Protected tag private key does not match the repository trust root.'
    }

    $tagOutput = @(& git -C $RepoRoot `
        -c gpg.format=ssh `
        -c "user.signingKey=$temporaryPrivateKey" `
        tag -s -m $Message $Tag $ExpectedRevision 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0) { throw "Signed tag creation failed: $($tagOutput -join [Environment]::NewLine)" }
    $tagCreated = $true

    $verifyOutput = @(& git -C $RepoRoot `
        -c gpg.format=ssh `
        -c "gpg.ssh.allowedSignersFile=$allowedSigners" `
        verify-tag --raw $Tag 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0 -or ($verifyOutput -join "`n") -notmatch 'Good "git" signature for fitness-ledger-release') {
        throw "Created release tag did not verify against the pinned trust root: $($verifyOutput -join [Environment]::NewLine)"
    }
} catch {
    if ($tagCreated) { & git -C $RepoRoot tag -d $Tag 2>&1 | Out-Null }
    throw
} finally {
    if ($null -ne $privateBytes) { [Array]::Clear($privateBytes, 0, $privateBytes.Length) }
    foreach ($path in @($temporaryPrivateKey, $publicProbe)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
}

Write-Host 'PASS: created and independently verified an SSH-signed release tag.'
Write-Host "  tag=$Tag"
Write-Host "  revision=$ExpectedRevision"
Write-Host "  signer=$expectedFingerprint"
exit 0
