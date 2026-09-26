[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedRevision,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

function Invoke-GitText([string[]] $Arguments) {
    $output = @(& git -C $script:RepoRoot @Arguments 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0) { throw "git failed: $($output -join [Environment]::NewLine)" }
    return @($output | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Write-Utf8([string] $Path, [string] $Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Invoke-Captured(
    [string] $Name,
    [scriptblock] $Command,
    [string] $LogPath
) {
    $started = [DateTime]::UtcNow
    $output = @(& $Command 2>&1 | ForEach-Object { [string] $_ })
    $exitCode = $LASTEXITCODE
    $finished = [DateTime]::UtcNow
    Write-Utf8 $LogPath (($output -join [Environment]::NewLine) + [Environment]::NewLine)
    if ($exitCode -ne 0) { throw "$Name failed with exit code $exitCode; raw log: $LogPath" }
    return [ordered]@{
        name = $Name
        exitCode = $exitCode
        startedUtc = $started.ToString('o')
        finishedUtc = $finished.ToString('o')
        log = [IO.Path]::GetFileName($LogPath)
        logSha256 = (Get-FileHash -LiteralPath $LogPath -Algorithm SHA256).Hash.ToUpperInvariant()
    }
}

$RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$head = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
if ($head -cne $ExpectedRevision) { throw "HEAD '$head' does not match '$ExpectedRevision'." }
if (@(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all')).Count -ne 0) {
    throw 'Release verification evidence requires a clean source tree.'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
if (Test-Path -LiteralPath $OutputDirectory) { throw "Refusing to reuse verification evidence: $OutputDirectory" }
$staging = "$OutputDirectory.staging-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $staging | Out-Null

try {
    $verifiedGradle = Join-Path $PSScriptRoot 'Invoke-GradleVerified.ps1'
    $runs = [Collections.Generic.List[object]]::new()
    $runs.Add((Invoke-Captured 'jvm-debug-tests' {
        & $verifiedGradle -Tasks @(':app:testDebugUnitTest') -GradleArguments @('--rerun-tasks', '--dependency-verification=strict')
    } (Join-Path $staging 'jvm-debug-tests.log')))
    $runs.Add((Invoke-Captured 'jvm-release-tests' {
        & $verifiedGradle -Tasks @(':app:testReleaseUnitTest') -GradleArguments @('--rerun-tasks', '--dependency-verification=strict')
    } (Join-Path $staging 'jvm-release-tests.log')))
    $runs.Add((Invoke-Captured 'lint-release' {
        & $verifiedGradle -Tasks @(':app:lintRelease') -GradleArguments @('--rerun-tasks', '--dependency-verification=strict')
    } (Join-Path $staging 'lint-release.log')))
    $runs.Add((Invoke-Captured 'dependency-verification-gate' {
        & (Join-Path $PSScriptRoot 'Test-ReleaseDependencyVerificationGate.ps1')
    } (Join-Path $staging 'dependency-verification-gate.log')))
    $runs.Add((Invoke-Captured 'powershell-parser-gate' {
        & (Join-Path $PSScriptRoot 'Test-PowerShellParser.ps1')
    } (Join-Path $staging 'powershell-parser-gate.log')))

    $rawDirectory = Join-Path $staging 'raw-results'
    New-Item -ItemType Directory -Path $rawDirectory | Out-Null
    $resultSources = @(
        @{ Name = 'jvm-debug-junit'; Path = (Join-Path $RepoRoot 'app\build\test-results\testDebugUnitTest') },
        @{ Name = 'jvm-release-junit'; Path = (Join-Path $RepoRoot 'app\build\test-results\testReleaseUnitTest') },
        @{ Name = 'lint'; Path = (Join-Path $RepoRoot 'app\build\reports') }
    )
    foreach ($source in $resultSources) {
        if (-not (Test-Path -LiteralPath $source.Path -PathType Container)) {
            throw "Required raw result directory is missing: $($source.Path)"
        }
        $destination = Join-Path $rawDirectory $source.Name
        New-Item -ItemType Directory -Path $destination | Out-Null
        if ($source.Name -eq 'lint') {
            $lintFiles = @(Get-ChildItem -LiteralPath $source.Path -File -Filter 'lint-results-release*')
            if ($lintFiles.Count -eq 0) { throw 'Release lint produced no raw reports.' }
            $lintFiles | Copy-Item -Destination $destination
        } else {
            Copy-Item -Path (Join-Path $source.Path '*') -Destination $destination -Recurse
        }
    }

    $jvmResults = [ordered]@{}
    foreach ($variant in @('debug', 'release')) {
        $junitRoot = Join-Path $rawDirectory "jvm-$variant-junit"
        $junitFiles = @(Get-ChildItem -LiteralPath $junitRoot -File -Filter 'TEST-*.xml' -Recurse)
        if ($junitFiles.Count -lt 1) { throw "No $variant JVM JUnit XML was captured." }
        $tests = 0
        $failures = 0
        $errors = 0
        $skipped = 0
        foreach ($junitFile in $junitFiles) {
            [xml] $xml = Get-Content -LiteralPath $junitFile.FullName -Raw -Encoding UTF8
            $suites = @($xml.SelectNodes('//testsuite[not(ancestor::testsuite)]'))
            if ($suites.Count -lt 1) { throw "JVM JUnit XML has no testsuite: $($junitFile.FullName)" }
            foreach ($suite in $suites) {
                $cases = @($suite.SelectNodes('./testcase'))
                $actualFailures = @($suite.SelectNodes('./testcase/failure')).Count
                $actualErrors = @($suite.SelectNodes('./testcase/error')).Count
                $actualSkipped = @($suite.SelectNodes('./testcase/skipped')).Count
                if (@($suite.SelectNodes('.//testsuite')).Count -ne 0 -or
                    [int] $suite.tests -ne $cases.Count -or
                    [int] $suite.failures -ne $actualFailures -or
                    [int] $suite.errors -ne $actualErrors -or
                    [int] $suite.skipped -ne $actualSkipped) {
                    throw "JVM JUnit testsuite summary does not match testcase outcomes: $($junitFile.FullName)"
                }
                $tests += [int] $suite.tests
                $failures += [int] $suite.failures
                $errors += [int] $suite.errors
                $skipped += [int] $suite.skipped
            }
        }
        if ($tests -lt 1 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Captured $variant JVM JUnit results are empty, failed, or skipped."
        }
        $jvmResults[$variant] = [ordered]@{
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
        }
    }

    $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $javaVersion = @(& $javaExecutable -version 2>&1 | ForEach-Object { [string] $_ })
    $javaVersionExitCode = $LASTEXITCODE
    if ($javaVersionExitCode -ne 0) { throw 'Could not capture the Java tool version.' }
    $gradleVersion = @(& (Join-Path $RepoRoot 'gradlew.bat') --version --console=plain 2>&1 | ForEach-Object { [string] $_ })
    $gradleVersionExitCode = $LASTEXITCODE
    if ($gradleVersionExitCode -ne 0) { throw 'Could not capture the Gradle tool version.' }
    $powershellExecutable = (Get-Process -Id $PID).Path
    $tools = [ordered]@{
        java = [ordered]@{
            versionOutput = $javaVersion
            versionExitCode = $javaVersionExitCode
            executableSha256 = (Get-FileHash -LiteralPath $javaExecutable -Algorithm SHA256).Hash.ToUpperInvariant()
        }
        gradle = [ordered]@{
            versionOutput = $gradleVersion
            versionExitCode = $gradleVersionExitCode
            wrapperSha256 = (Get-FileHash -LiteralPath (Join-Path $RepoRoot 'gradlew.bat') -Algorithm SHA256).Hash.ToUpperInvariant()
        }
        powershell = [ordered]@{
            version = $PSVersionTable.PSVersion.ToString()
            currentProcessExitBoundary = 'captured by the parent release gate; nonzero prevents evidence promotion'
            executableSha256 = (Get-FileHash -LiteralPath $powershellExecutable -Algorithm SHA256).Hash.ToUpperInvariant()
        }
        operatingSystem = [Environment]::OSVersion.VersionString
    }
    Write-Utf8 (Join-Path $staging 'tool-versions.json') (($tools | ConvertTo-Json -Depth 8) + "`n")

    # Verification runs and tool probes can take minutes. Recheck their source
    # identity before writing any PASS manifest; a concurrent commit can leave
    # status clean while changing HEAD, so both checks are required.
    $postVerificationRevision = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
    if ($postVerificationRevision -cne $ExpectedRevision) {
        throw "Git HEAD changed during release verification: $ExpectedRevision -> $postVerificationRevision"
    }
    $postVerificationStatus = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
    if ($postVerificationStatus.Count -ne 0) {
        throw "Source/index changed during release verification. Dirty entries:`n$($postVerificationStatus -join [Environment]::NewLine)"
    }

    $evidenceFiles = @(Get-ChildItem -LiteralPath $staging -File -Recurse | Sort-Object FullName)
    $manifest = [ordered]@{
        schema = 'fitness-ledger-release-verification-evidence-v1'
        status = 'PASS'
        revision = $ExpectedRevision
        cleanSource = $true
        runs = @($runs)
        jvmResults = $jvmResults
        files = @($evidenceFiles | ForEach-Object {
            [ordered]@{
                path = ([IO.Path]::GetRelativePath($staging, $_.FullName) -replace '\\', '/')
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
            }
        })
    }
    Write-Utf8 (Join-Path $staging 'release-verification-evidence.json') (($manifest | ConvertTo-Json -Depth 20) + "`n")
    $allFiles = @(Get-ChildItem -LiteralPath $staging -File -Recurse | Sort-Object FullName)
    $checksumLines = @($allFiles | ForEach-Object {
        "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $([IO.Path]::GetRelativePath($staging, $_.FullName) -replace '\\', '/')"
    })
    Write-Utf8 (Join-Path $staging 'SHA256SUMS.txt') (($checksumLines -join "`n") + "`n")
    Move-Item -LiteralPath $staging -Destination $OutputDirectory
} finally {
    if (Test-Path -LiteralPath $staging -PathType Container) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}

Write-Host 'PASS: raw JVM Debug/Release, Lint, dependency and parser evidence captured.'
Write-Host "  revision=$ExpectedRevision"
Write-Host "  evidence=$OutputDirectory"
exit 0
