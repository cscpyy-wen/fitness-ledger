[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

function Write-Utf8([string] $Path, [string] $Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function New-FileEntry([string] $Root, [string] $Path) {
    $fullPath = Join-Path $Root ($Path -replace '/', [IO.Path]::DirectorySeparatorChar)
    $file = Get-Item -LiteralPath $fullPath
    return [ordered]@{
        path = $Path
        bytes = $file.Length
        sha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToUpperInvariant()
    }
}

function Write-ExactChecksums([string] $Root) {
    $checksumPath = Join-Path $Root 'SHA256SUMS.txt'
    $lines = @(
        Get-ChildItem -LiteralPath $Root -File -Recurse |
            Where-Object { $_.FullName -cne $checksumPath } |
            Sort-Object FullName |
            ForEach-Object {
                $relative = [IO.Path]::GetRelativePath($Root, $_.FullName) -replace '\\', '/'
                "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $relative"
            }
    )
    Write-Utf8 $checksumPath (($lines -join "`n") + "`n")
}

function Refresh-ManifestFileEntry([string] $Root, [string] $ManifestName, [string] $RelativePath) {
    $manifestPath = Join-Path $Root $ManifestName
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $matches = @($manifest.files | Where-Object { [string] $_.path -ceq $RelativePath })
    if ($matches.Count -ne 1) { throw "Synthetic manifest entry is missing: $RelativePath" }
    $entry = New-FileEntry $Root $RelativePath
    $matches[0].bytes = $entry.bytes
    $matches[0].sha256 = $entry.sha256
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $Root
}

function Copy-Case([string] $Source, [string] $Destination) {
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse
}

function Assert-Fails([string] $Label, [scriptblock] $Action) {
    $failed = $false
    try {
        & $Action
    } catch {
        $failed = $true
    }
    if (-not $failed) { throw "Expected fail-closed rejection: $Label" }
}

function New-ReleaseVerificationBundle([string] $Root, [string] $Revision) {
    New-Item -ItemType Directory -Path $Root | Out-Null
    foreach ($name in @(
        'jvm-debug-tests',
        'jvm-release-tests',
        'lint-release',
        'dependency-verification-gate',
        'powershell-parser-gate'
    )) {
        Write-Utf8 (Join-Path $Root "$name.log") "PASS: $name`n"
    }
    $debugDirectory = Join-Path $Root 'raw-results\jvm-debug-junit'
    $releaseDirectory = Join-Path $Root 'raw-results\jvm-release-junit'
    $lintDirectory = Join-Path $Root 'raw-results\lint'
    New-Item -ItemType Directory -Path $debugDirectory, $releaseDirectory, $lintDirectory | Out-Null
    $junit = '<testsuite name="synthetic" tests="1" failures="0" errors="0" skipped="0"><testcase name="passes" classname="Evidence"/></testsuite>'
    Write-Utf8 (Join-Path $debugDirectory 'TEST-debug.xml') ($junit + "`n")
    Write-Utf8 (Join-Path $releaseDirectory 'TEST-release.xml') ($junit + "`n")
    Write-Utf8 (Join-Path $lintDirectory 'lint-results-release.xml') '<issues format="6" by="lint" />'
    $toolHash = 'A' * 64
    $tools = [ordered]@{
        java = [ordered]@{ versionOutput = @('java synthetic'); versionExitCode = 0; executableSha256 = $toolHash }
        gradle = [ordered]@{ versionOutput = @('gradle synthetic'); versionExitCode = 0; wrapperSha256 = $toolHash }
        powershell = [ordered]@{ version = 'synthetic'; executableSha256 = $toolHash }
    }
    Write-Utf8 (Join-Path $Root 'tool-versions.json') (($tools | ConvertTo-Json -Depth 10) + "`n")
    $runNames = @(
        'jvm-debug-tests',
        'jvm-release-tests',
        'lint-release',
        'dependency-verification-gate',
        'powershell-parser-gate'
    )
    $runs = @($runNames | ForEach-Object {
        $log = "$_.log"
        [ordered]@{
            name = $_
            exitCode = 0
            startedUtc = '2026-09-01T00:00:00.0000000+00:00'
            finishedUtc = '2026-09-01T00:00:01.0000000+00:00'
            log = $log
            logSha256 = (Get-FileHash -LiteralPath (Join-Path $Root $log) -Algorithm SHA256).Hash.ToUpperInvariant()
        }
    })
    $files = @(Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object FullName | ForEach-Object {
        New-FileEntry $Root ([IO.Path]::GetRelativePath($Root, $_.FullName) -replace '\\', '/')
    })
    $manifest = [ordered]@{
        schema = 'fitness-ledger-release-verification-evidence-v1'
        status = 'PASS'
        revision = $Revision
        cleanSource = $true
        runs = $runs
        jvmResults = [ordered]@{
            debug = [ordered]@{ tests = 1; failures = 0; errors = 0; skipped = 0 }
            release = [ordered]@{ tests = 1; failures = 0; errors = 0; skipped = 0 }
        }
        files = $files
    }
    Write-Utf8 (Join-Path $Root 'release-verification-evidence.json') (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $Root
}

function New-InstrumentationBundle(
    [string] $Root,
    [string] $Revision,
    [string] $ReleaseHash,
    [long] $ReleaseBytes
) {
    $junitDirectory = Join-Path $Root 'raw-junit'
    $artifactDirectory = Join-Path $Root 'tested-artifacts'
    New-Item -ItemType Directory -Path $junitDirectory, $artifactDirectory | Out-Null
    Write-Utf8 (Join-Path $Root 'gradle-connected-debug-android-test.log') "PASS: connectedDebugAndroidTest`n"
    $device = [ordered]@{
        serial = 'emulator-5560'
        state = 'device'
        apiLevel = 36
        androidRelease = '16'
        fingerprint = 'synthetic/fingerprint'
        manufacturer = 'Google'
        model = 'Synthetic Emulator'
        abi = 'x86_64'
    }
    $deviceLines = @($device.Keys | ForEach-Object { "$_=$($device[$_])" })
    Write-Utf8 (Join-Path $Root 'device.properties') (($deviceLines -join "`n") + "`n")
    Write-Utf8 (Join-Path $junitDirectory 'TEST-instrumentation.xml') '<testsuite name="synthetic" tests="2" failures="0" errors="0" skipped="0"><testcase name="one" classname="Evidence"/><testcase name="two" classname="Evidence"/></testsuite>'
    Write-Utf8 (Join-Path $artifactDirectory 'app-debug.apk') 'synthetic debug app APK'
    Write-Utf8 (Join-Path $artifactDirectory 'app-debug-androidTest.apk') 'synthetic instrumentation APK'
    $appEntry = New-FileEntry $Root 'tested-artifacts/app-debug.apk'
    $appEntry.role = 'debug-app-under-test'
    $appEntry.fileName = 'app-debug.apk'
    $testEntry = New-FileEntry $Root 'tested-artifacts/app-debug-androidTest.apk'
    $testEntry.role = 'debug-instrumentation-test'
    $testEntry.fileName = 'app-debug-androidTest.apk'
    $manifest = [ordered]@{
        schema = 'fitness-ledger-instrumentation-evidence-v1'
        status = 'PASS'
        source = [ordered]@{
            revision = $Revision
            revisionAfterRun = $Revision
            clean = $true
            cleanBeforeRun = $true
            cleanAfterRun = $true
        }
        invocation = [ordered]@{
            tasks = @('clean', ':app:connectedDebugAndroidTest')
            expectedTestCount = 2
            startedUtc = '2026-09-01T00:00:00.0000000+00:00'
            finishedUtc = '2026-09-01T00:00:01.0000000+00:00'
            androidSerialWasExplicit = $true
            outputLocationOutsideRepository = $true
            releaseReferenceOutsideRepository = $true
        }
        device = $device
        result = [ordered]@{ tests = 2; failures = 0; errors = 0; skipped = 0 }
        testedArtifacts = @($appEntry, $testEntry)
        releaseArtifactReference = [ordered]@{
            fileName = 'app-release.apk'
            sha256 = $ReleaseHash
            bytes = $ReleaseBytes
            directlyExercisedByThisRun = $false
        }
        tools = [ordered]@{
            adbSha256 = ('B' * 64)
            adbVersion = @('Android Debug Bridge synthetic')
            verifiedGradleEntryPoint = 'scripts/Invoke-GradleVerified.ps1'
        }
        rawEvidence = [ordered]@{
            gradleLog = New-FileEntry $Root 'gradle-connected-debug-android-test.log'
            deviceProperties = New-FileEntry $Root 'device.properties'
            junitXml = @((New-FileEntry $Root 'raw-junit/TEST-instrumentation.xml'))
        }
    }
    Write-Utf8 (Join-Path $Root 'instrumentation-evidence.json') (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $Root
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) "fitness-release-evidence-test-$([Guid]::NewGuid().ToString('N'))"
$resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Synthetic evidence test root escaped the operating-system temporary directory.'
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    Assert-Fails 'repository-local evidence output' {
        Assert-PathOutsideRepository $PSScriptRoot (Join-Path $PSScriptRoot 'inside') 'Synthetic output'
    }
    $externalPath = Assert-PathOutsideRepository $PSScriptRoot $testRoot 'Synthetic external output'
    if ($externalPath -cne $resolvedTestRoot) { throw 'External evidence path normalization is stale.' }
    $documentA = Join-Path $testRoot 'document-a.md'
    $documentB = Join-Path $testRoot 'document-b.md'
    Write-Utf8 $documentA 'version/hash/tag tokens plus trusted conclusion'
    Write-Utf8 $documentB 'version/hash/tag tokens plus trusted conclusion'
    [void](Assert-FilesByteIdentical $documentA $documentB 'Synthetic signed document')
    Write-Utf8 $documentB 'version/hash/tag tokens plus altered conclusion'
    Assert-Fails 'token-preserving delivery document rewrite' {
        Assert-FilesByteIdentical $documentA $documentB 'Synthetic signed document'
    }
    $revision = '1' * 40
    $releaseHash = 'C' * 64
    $releaseBytes = 123
    $releaseTemplate = Join-Path $testRoot 'release-template'
    $instrumentationTemplate = Join-Path $testRoot 'instrumentation-template'
    New-ReleaseVerificationBundle $releaseTemplate $revision
    New-InstrumentationBundle $instrumentationTemplate $revision $releaseHash $releaseBytes

    [void](Assert-ReleaseVerificationEvidenceBundle $releaseTemplate $revision)
    [void](Assert-InstrumentationEvidenceBundle $instrumentationTemplate $revision $releaseHash $releaseBytes)

    $lintRelative = 'raw-results/lint/lint-results-release.xml'
    foreach ($severity in @('Fatal', 'Error')) {
        $case = Join-Path $testRoot "release-lint-$severity"
        Copy-Case $releaseTemplate $case
        Write-Utf8 (Join-Path $case $lintRelative) "<issues format=`"6`"><issue id=`"Synthetic`" severity=`"$severity`"/></issues>"
        Refresh-ManifestFileEntry $case 'release-verification-evidence.json' $lintRelative
        Assert-Fails "coordinated lint $severity rewrite" { Assert-ReleaseVerificationEvidenceBundle $case $revision }
    }
    $invalidLintReports = [ordered]@{
        'malformed' = '<issues format="6"><issue'
        'wrong-root' = '<html />'
        'missing-format' = '<issues />'
        'missing-severity' = '<issues format="6"><issue id="Synthetic"/></issues>'
        'unknown-severity' = '<issues format="6"><issue id="Synthetic" severity="Success"/></issues>'
        'nested-issue' = '<issues format="6"><issue severity="Warning"><issue severity="Fatal"/></issue></issues>'
        'dtd' = '<!DOCTYPE issues [<!ENTITY severity "Warning">]><issues format="6"><issue severity="&severity;"/></issues>'
    }
    foreach ($name in $invalidLintReports.Keys) {
        $case = Join-Path $testRoot "release-lint-$name"
        Copy-Case $releaseTemplate $case
        Write-Utf8 (Join-Path $case $lintRelative) $invalidLintReports[$name]
        Refresh-ManifestFileEntry $case 'release-verification-evidence.json' $lintRelative
        Assert-Fails "invalid lint XML: $name" { Assert-ReleaseVerificationEvidenceBundle $case $revision }
    }
    foreach ($replacement in @('none', 'html', 'txt')) {
        $case = Join-Path $testRoot "release-lint-xml-missing-$replacement"
        Copy-Case $releaseTemplate $case
        Remove-Item -LiteralPath (Join-Path $case $lintRelative)
        $manifestPath = Join-Path $case 'release-verification-evidence.json'
        $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $manifest.files = @($manifest.files | Where-Object { $_.path -cne $lintRelative })
        if ($replacement -ne 'none') {
            $replacementPath = "raw-results/lint/lint-results-release.$replacement"
            Write-Utf8 (Join-Path $case $replacementPath) 'Synthetic display report; no machine-readable XML.'
            $manifest.files += New-FileEntry $case $replacementPath
        }
        Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
        Write-ExactChecksums $case
        Assert-Fails "missing lint XML with replacement $replacement" { Assert-ReleaseVerificationEvidenceBundle $case $revision }
    }
    $case = Join-Path $testRoot 'release-lint-warnings-and-baseline-info'
    Copy-Case $releaseTemplate $case
    Write-Utf8 (Join-Path $case $lintRelative) '<issues format="6"><issue id="SyntheticWarning" severity="Warning"/><issue id="LintBaseline" severity="Information"/><issue id="SyntheticInfo" severity="Informational"/><issue id="SyntheticHint" severity="Hint"/><issue id="SyntheticIgnored" severity="Ignore"/></issues>'
    Refresh-ManifestFileEntry $case 'release-verification-evidence.json' $lintRelative
    [void](Assert-ReleaseVerificationEvidenceBundle $case $revision)
    Write-Host 'PASS: 13 lint XML cases cover blocking errors, malformed/missing reports, and allowed warnings/hints.'

    $case = Join-Path $testRoot 'release-run-traversal'
    Copy-Case $releaseTemplate $case
    $manifestPath = Join-Path $case 'release-verification-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.runs[0].log = '../outside.log'
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'release run log path traversal' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-run-alias'
    Copy-Case $releaseTemplate $case
    $manifestPath = Join-Path $case 'release-verification-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.runs[0].log = 'jvm-release-tests.log'
    $manifest.runs[0].logSha256 = (Get-FileHash -LiteralPath (Join-Path $case 'jvm-release-tests.log') -Algorithm SHA256).Hash.ToUpperInvariant()
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'release run log alias' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-junit-failure'
    Copy-Case $releaseTemplate $case
    Write-Utf8 (Join-Path $case 'raw-results\jvm-debug-junit\TEST-debug.xml') '<testsuite tests="1" failures="1" errors="0"><testcase name="fails"/></testsuite>'
    Refresh-ManifestFileEntry $case 'release-verification-evidence.json' 'raw-results/jvm-debug-junit/TEST-debug.xml'
    Assert-Fails 'coordinated failed JVM JUnit rewrite' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-junit-skipped'
    Copy-Case $releaseTemplate $case
    Write-Utf8 (Join-Path $case 'raw-results\jvm-debug-junit\TEST-debug.xml') '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase name="skipped"><skipped/></testcase></testsuite>'
    $manifestPath = Join-Path $case 'release-verification-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $entry = New-FileEntry $case 'raw-results/jvm-debug-junit/TEST-debug.xml'
    $fileMatch = @($manifest.files | Where-Object { [string] $_.path -ceq $entry.path })
    $fileMatch[0].bytes = $entry.bytes
    $fileMatch[0].sha256 = $entry.sha256
    $manifest.jvmResults.debug.skipped = 1
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'all-skipped JVM JUnit rewrite' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-junit-summary-tamper'
    Copy-Case $releaseTemplate $case
    $manifestPath = Join-Path $case 'release-verification-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.jvmResults.debug.tests = 2
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'JVM result summary mismatch' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-junit-hidden-failure'
    Copy-Case $releaseTemplate $case
    Write-Utf8 (Join-Path $case 'raw-results\jvm-debug-junit\TEST-debug.xml') '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="hidden"><failure message="hidden failure"/></testcase></testsuite>'
    Refresh-ManifestFileEntry $case 'release-verification-evidence.json' 'raw-results/jvm-debug-junit/TEST-debug.xml'
    Assert-Fails 'JVM testcase failure hidden by suite attributes' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-extra-raw'
    Copy-Case $releaseTemplate $case
    Write-Utf8 (Join-Path $case 'unlisted.log') 'hidden evidence'
    Write-ExactChecksums $case
    Assert-Fails 'unlisted release raw evidence' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'release-missing-checksum'
    Copy-Case $releaseTemplate $case
    Remove-Item -LiteralPath (Join-Path $case 'SHA256SUMS.txt')
    Assert-Fails 'missing release checksum file' { Assert-ReleaseVerificationEvidenceBundle $case $revision }

    $case = Join-Path $testRoot 'instrumentation-device-mismatch'
    Copy-Case $instrumentationTemplate $case
    $manifestPath = Join-Path $case 'instrumentation-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.device.apiLevel = 35
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'device summary/raw mismatch' { Assert-InstrumentationEvidenceBundle $case $revision $releaseHash $releaseBytes }

    $case = Join-Path $testRoot 'instrumentation-overclaim'
    Copy-Case $instrumentationTemplate $case
    $manifestPath = Join-Path $case 'instrumentation-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.releaseArtifactReference.directlyExercisedByThisRun = $true
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'release APK instrumentation overclaim' { Assert-InstrumentationEvidenceBundle $case $revision $releaseHash $releaseBytes }

    $case = Join-Path $testRoot 'instrumentation-apk-tamper'
    Copy-Case $instrumentationTemplate $case
    Write-Utf8 (Join-Path $case 'tested-artifacts\app-debug.apk') 'replaced debug app APK'
    Write-ExactChecksums $case
    Assert-Fails 'tested debug APK replacement' { Assert-InstrumentationEvidenceBundle $case $revision $releaseHash $releaseBytes }

    $case = Join-Path $testRoot 'instrumentation-junit-tamper'
    Copy-Case $instrumentationTemplate $case
    Write-Utf8 (Join-Path $case 'raw-junit\TEST-instrumentation.xml') '<testsuite tests="2" failures="1" errors="0" skipped="0" />'
    $manifestPath = Join-Path $case 'instrumentation-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $entry = New-FileEntry $case 'raw-junit/TEST-instrumentation.xml'
    $manifest.rawEvidence.junitXml[0].bytes = $entry.bytes
    $manifest.rawEvidence.junitXml[0].sha256 = $entry.sha256
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'coordinated failed instrumentation JUnit rewrite' { Assert-InstrumentationEvidenceBundle $case $revision $releaseHash $releaseBytes }

    $case = Join-Path $testRoot 'instrumentation-junit-hidden-failure'
    Copy-Case $instrumentationTemplate $case
    Write-Utf8 (Join-Path $case 'raw-junit\TEST-instrumentation.xml') '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase name="hidden"><failure message="hidden failure"/></testcase><testcase name="passes"/></testsuite>'
    $manifestPath = Join-Path $case 'instrumentation-evidence.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $entry = New-FileEntry $case 'raw-junit/TEST-instrumentation.xml'
    $manifest.rawEvidence.junitXml[0].bytes = $entry.bytes
    $manifest.rawEvidence.junitXml[0].sha256 = $entry.sha256
    Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 30) + "`n")
    Write-ExactChecksums $case
    Assert-Fails 'instrumentation testcase failure hidden by suite attributes' { Assert-InstrumentationEvidenceBundle $case $revision $releaseHash $releaseBytes }

    $deliveryPolicyRoot = Join-Path $testRoot 'delivery-apk-policy'
    $deliveryArchive = Join-Path $deliveryPolicyRoot '历史版本'
    $deliveryEvidence = Join-Path $deliveryPolicyRoot 'v0.0.0-test-release-evidence'
    $testedArtifacts = Join-Path $deliveryEvidence 'instrumentation\tested-artifacts'
    New-Item -ItemType Directory -Path $deliveryArchive, $testedArtifacts | Out-Null
    $allowedDeliveryApks = @(
        (Join-Path $deliveryPolicyRoot '健身减脂-v0.0.0-test.apk'),
        (Join-Path $deliveryEvidence 'app-release.apk'),
        (Join-Path $testedArtifacts 'app-debug.apk'),
        (Join-Path $testedArtifacts 'app-debug-androidTest.apk')
    )
    foreach ($path in $allowedDeliveryApks) { Write-Utf8 $path 'synthetic APK' }
    Write-Utf8 (Join-Path $deliveryArchive 'historical.apk') 'archived APK'
    [void](Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive $allowedDeliveryApks)

    $rogueApk = Join-Path $deliveryEvidence 'rogue.apk'
    Write-Utf8 $rogueApk 'rogue APK'
    Assert-Fails 'unlisted nested APK' { Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive $allowedDeliveryApks }
    Remove-Item -LiteralPath $rogueApk

    $extraTestApk = Join-Path $testedArtifacts 'extra.apk'
    Write-Utf8 $extraTestApk 'extra instrumentation APK'
    Assert-Fails 'extra instrumentation APK' { Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive $allowedDeliveryApks }
    Remove-Item -LiteralPath $extraTestApk

    $hiddenApk = Join-Path $deliveryEvidence 'hidden.apk'
    Write-Utf8 $hiddenApk 'hidden APK'
    (Get-Item -LiteralPath $hiddenApk -Force).Attributes = [IO.FileAttributes]::Hidden
    Assert-Fails 'hidden unlisted APK' { Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive $allowedDeliveryApks }
    Remove-Item -LiteralPath $hiddenApk -Force

    $outsideAllowed = Join-Path $testRoot 'outside.apk'
    Write-Utf8 $outsideAllowed 'outside APK'
    Assert-Fails 'allowed APK path escaping delivery' {
        Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive @($allowedDeliveryApks + $outsideAllowed)
    }

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $junctionTarget = Join-Path $testRoot 'junction-target'
        $junctionPath = Join-Path $deliveryEvidence 'junction'
        New-Item -ItemType Directory -Path $junctionTarget | Out-Null
        Write-Utf8 (Join-Path $junctionTarget 'hidden-via-junction.apk') 'junction APK'
        New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget | Out-Null
        Assert-Fails 'delivery junction' { Assert-DeliveryApkPlacement $deliveryPolicyRoot $deliveryArchive $allowedDeliveryApks }
        Remove-Item -LiteralPath $junctionPath -Force
    }

    Write-Host 'PASS: release and instrumentation evidence bundles fail closed under tampering.'
} finally {
    if (Test-Path -LiteralPath $resolvedTestRoot -PathType Container) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

exit 0
