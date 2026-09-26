[CmdletBinding()]
param(
    [string] $RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')),
    [ValidateSet('Reject', 'Accept')]
    [string] $ExpectedBehavior = 'Reject'
)

# Regression for persistent source changes during release evidence capture.
# Read-only toward RepositoryRoot. All writes and Git operations occur in a new,
# uniquely named directory under the OS temp folder. No Gradle, Java, APK tools,
# network APIs, real keys, emulator or provider are invoked. Fixtures are retained.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
$captureSource = Join-Path $RepositoryRoot 'scripts\Invoke-ReleaseVerificationEvidence.ps1'
$commonSource = Join-Path $RepositoryRoot 'scripts\ReleaseSigning.Common.ps1'
foreach ($path in @($captureSource, $commonSource)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required audited source missing: $path" }
}
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$scratch = Join-Path $tempBase ('fitness-source-drift-audit-' + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $scratch) { throw 'Refusing to reuse an existing scratch directory.' }
if (-not $scratch.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Scratch directory escaped OS temp.'
}
New-Item -ItemType Directory -Path $scratch | Out-Null
function Write-SyntheticUtf8([string] $Path, [string] $Text) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($scratch + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Synthetic write escaped the fresh scratch directory.'
    }
    [IO.File]::WriteAllText($full, $Text, [Text.UTF8Encoding]::new($false))
}
. $commonSource
$javaBefore = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
try {
    $jdkBin = Join-Path $scratch 'jdk\bin'
    New-Item -ItemType Directory -Path $jdkBin | Out-Null
    Write-SyntheticUtf8 (Join-Path $jdkBin 'java.exe') 'Synthetic bytes for a hash; never executed.'
    [Environment]::SetEnvironmentVariable('JAVA_HOME', (Join-Path $scratch 'jdk'), 'Process')
    foreach ($mode in @('clean', 'uncommitted', 'untracked', 'committed')) {
        $fakeRepo = Join-Path $scratch $mode
        $fakeScripts = Join-Path $fakeRepo 'scripts'
        New-Item -ItemType Directory -Path $fakeScripts | Out-Null
        $capture = Get-Content -LiteralPath $captureSource -Raw -Encoding UTF8
        $javaInvocation = '$javaVersion = @(& $javaExecutable -version 2>&1 | ForEach-Object { [string] $_ })'
        if (-not $capture.Contains($javaInvocation)) { throw 'Audited Java version invocation changed; inspect fixture adapter.' }
        # Only the external Java version probe is replaced. Source-state checks,
        # manifest generation, and the real production bundle validator run intact.
        $capture = $capture.Replace($javaInvocation, '$javaVersion = @(''SYNTHETIC JAVA VERSION STUB''); $global:LASTEXITCODE = 0')
        Write-SyntheticUtf8 (Join-Path $fakeScripts 'Invoke-ReleaseVerificationEvidence.ps1') $capture
        Write-SyntheticUtf8 (Join-Path $fakeRepo '.gitignore') "app/build/`n"
        Write-SyntheticUtf8 (Join-Path $fakeRepo 'tracked.txt') 'original committed source'
        Write-SyntheticUtf8 (Join-Path $fakeRepo 'gradlew.bat') "@echo off`necho SYNTHETIC GRADLE VERSION STUB`nexit /b 0`n"
        $stub = @'
param([string[]] $Tasks, [string[]] $GradleArguments)
$repo = Split-Path -Parent $PSScriptRoot
__SOURCE_CHANGE__
if (__COMMIT__) {
    & git -C $repo diff --quiet
    if ($LASTEXITCODE -ne 0) {
        & git -C $repo add tracked.txt
        & git -C $repo -c user.name='Synthetic Audit' -c user.email='audit@example.invalid' -c commit.gpgsign=false commit -m 'change during checks' --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic commit failed.' }
    }
}
foreach ($variant in @('Debug','Release')) {
    $directory = Join-Path $repo "app\build\test-results\test${variant}UnitTest"
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $directory 'TEST-synthetic.xml'), '<testsuite name="synthetic" tests="1" failures="0" errors="0" skipped="0"><testcase name="synthetic"/></testsuite>')
}
$reports = Join-Path $repo 'app\build\reports'
New-Item -ItemType Directory -Path $reports -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $reports 'lint-results-release.xml'), '<issues format="6"/>')
Write-Output 'SYNTHETIC TEST STUB: no Gradle executed'
$global:LASTEXITCODE = 0
'@
        $commitFlag = if ($mode -eq 'committed') { '$true' } else { '$false' }
        $sourceChange = switch ($mode) {
            'clean' { '# No concurrent source mutation.' }
            'untracked' { '[IO.File]::WriteAllText((Join-Path $repo ''new-source.txt''), ''new untracked source while verification ran'')' }
            default { '[IO.File]::WriteAllText((Join-Path $repo ''tracked.txt''), ''source changed while verification ran'')' }
        }
        Write-SyntheticUtf8 (Join-Path $fakeScripts 'Invoke-GradleVerified.ps1') ($stub.Replace('__COMMIT__', $commitFlag).Replace('__SOURCE_CHANGE__', $sourceChange))
        foreach ($name in @('Test-ReleaseDependencyVerificationGate.ps1', 'Test-PowerShellParser.ps1')) {
            Write-SyntheticUtf8 (Join-Path $fakeScripts $name) '$global:LASTEXITCODE = 0'
        }
        & git -C $fakeRepo init --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic init failed.' }
        & git -C $fakeRepo add .
        & git -C $fakeRepo -c user.name='Synthetic Audit' -c user.email='audit@example.invalid' -c commit.gpgsign=false commit -m 'synthetic clean fixture' --quiet
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic initial commit failed.' }
        $originalRevision = (& git -C $fakeRepo rev-parse HEAD).Trim()
        $output = Join-Path $scratch "$mode-evidence"
        & (Join-Path $fakeScripts 'Invoke-ReleaseVerificationEvidence.ps1') -ExpectedRevision $originalRevision -OutputDirectory $output
        $captureExit = $LASTEXITCODE
        if ($ExpectedBehavior -eq 'Reject' -and $mode -ne 'clean') {
            if ($captureExit -eq 0) { throw "FAIL: $mode source drift was accepted." }
            if (Test-Path -LiteralPath $output) { throw "FAIL: $mode drift left promoted evidence." }
            $passManifests = @(Get-ChildItem -LiteralPath $scratch -Filter 'release-verification-evidence.json' -File -Recurse |
                Where-Object { $_.FullName.StartsWith("$output.staging-", [StringComparison]::OrdinalIgnoreCase) })
            if ($passManifests.Count -ne 0) { throw "FAIL: $mode drift left a staged PASS manifest." }
            Write-Host "PASS: $mode source drift rejected without promoted/staged PASS evidence."
            continue
        }
        if ($captureExit -ne 0) { throw "Expected $mode fixture acceptance; capture exited with $captureExit" }
        $bundle = Assert-ReleaseVerificationEvidenceBundle $output $originalRevision
        $postHead = (& git -C $fakeRepo rev-parse HEAD).Trim()
        $postStatus = @(& git -C $fakeRepo status --porcelain=v1 --untracked-files=all)
        [ordered]@{
            mode = $mode
            originalRevision = $originalRevision
            actualFinalRevision = $postHead
            actualFinalStatus = $postStatus
            evidenceRevision = $bundle.revision
            evidenceCleanSource = $bundle.cleanSource
            productionValidatorAccepted = $true
        } | ConvertTo-Json -Depth 5
    }
} finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaBefore, 'Process')
}
Write-Host "Synthetic fixtures retained at: $scratch"
Write-Host 'PASS: clean source accepted; tracked, untracked, and committed source drift checked.'
exit 0
