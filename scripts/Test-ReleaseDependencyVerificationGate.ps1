[CmdletBinding()]
param(
    [switch] $SkipGradleProbe
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')

function Assert-Contains([string] $Text, [string] $Needle, [string] $Label) {
    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required security text: $Needle"
    }
}

function Assert-DoesNotContain([string] $Text, [string] $Needle, [string] $Label) {
    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden security text: $Needle"
    }
}

$repoRoot = Get-ReleaseRepositoryRoot
$policyText = Get-Content -LiteralPath (Join-Path $repoRoot 'release-policy.properties') -Raw -Encoding UTF8
if ($policyText -notmatch '(?m)^requireStrictDependencyVerification=true\s*$') {
    throw 'release-policy.properties does not require strict dependency verification.'
}
if ($policyText -notmatch '(?m)^gradleDistributionNetworkTimeoutMs=60000\s*$') {
    throw 'release-policy.properties does not pin the Gradle distribution network timeout.'
}
if ($policyText -notmatch '(?m)^allowVerifiedLocalGradleDistributionArchive=true\s*$') {
    throw 'release-policy.properties does not allow only a hash-verified local Gradle distribution archive.'
}
if ($policyText -notmatch '(?m)^releaseArtifactSigningMode=external-apksigner\s*$') {
    throw 'release-policy.properties does not require external apksigner signing.'
}
if ($policyText -notmatch '(?m)^releaseSignatureSchemes=v2-only\s*$') {
    throw 'release-policy.properties does not require v2-only signing.'
}
if ($policyText -notmatch '(?m)^materialHashMode=git-blob-bytes-sha256\s*$') {
    throw 'release-policy.properties does not require Git-blob-byte material hashes.'
}
if ($policyText -notmatch '(?m)^minSdk=\d+\s*$' -or $policyText -notmatch '(?m)^targetSdk=\d+\s*$') {
    throw 'release-policy.properties does not pin minSdk/targetSdk.'
}
if ($policyText -notmatch '(?m)^releaseAbis=arm64-v8a,armeabi-v7a,x86_64\s*$' -or
    $policyText -notmatch '(?m)^requiredNativeLibraries=liblitert_jni\.so,libLiteRt\.so,libLiteRtClGlAccelerator\.so\s*$') {
    throw 'release-policy.properties does not exclude x86 or pin the complete LiteRT ABI payload.'
}
if ($policyText -notmatch '(?m)^gitTagSigningSshKeyFingerprint=SHA256:[A-Za-z0-9+/]+\s*$' -or
    $policyText -notmatch '(?m)^provenanceSignatureScheme=jca-detached-sha256\s*$') {
    throw 'release-policy.properties does not pin both Git tag and provenance signing identities.'
}

$gradlePropertiesText = Get-Content -LiteralPath (Join-Path $repoRoot 'gradle.properties') -Raw -Encoding UTF8
if ($gradlePropertiesText -notmatch '(?m)^org\.gradle\.daemon=false\s*$') {
    throw 'gradle.properties must disable the persistent Gradle daemon repository-wide.'
}

$wrapperPropertiesText = Get-Content -LiteralPath (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.properties') -Raw -Encoding UTF8
if ($wrapperPropertiesText -notmatch '(?m)^networkTimeout=60000\s*$' -or
    $wrapperPropertiesText -notmatch '(?m)^validateDistributionUrl=true\s*$') {
    throw 'Gradle wrapper must use the policy-pinned timeout and validate its distribution URL.'
}

$windowsWrapperText = Get-Content -LiteralPath (Join-Path $repoRoot 'gradlew.bat') -Raw -Encoding UTF8
foreach ($requiredWrapperText in @(
    'Resolve-GradleAsciiWorkspace.ps1',
    'FITNESS_GRADLE_ASCII_ACTIVE',
    'pushd "%FITNESS_ASCII_WORKSPACE%"',
    'popd',
    'endlocal & exit /b %FITNESS_REDIRECT_EXIT%'
)) {
    Assert-Contains $windowsWrapperText $requiredWrapperText 'gradlew.bat'
}
$pathResolverText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\Resolve-GradleAsciiWorkspace.ps1') -Raw -Encoding UTF8
foreach ($requiredResolverText in @(
    'FITNESS_ASCII_WORKSPACE=',
    'FITNESS_JAVA_HOME=',
    'javaExecutableSha256',
    'Get-FileHash',
    "@('gradlew.bat', 'settings.gradle.kts', 'app\build.gradle.kts')"
)) {
    Assert-Contains $pathResolverText $requiredResolverText 'Resolve-GradleAsciiWorkspace.ps1'
}

$settingsText = Get-Content -LiteralPath (Join-Path $repoRoot 'settings.gradle.kts') -Raw -Encoding UTF8
$appBuildText = Get-Content -LiteralPath (Join-Path $repoRoot 'app\build.gradle.kts') -Raw -Encoding UTF8
foreach ($gate in @(
    [ordered]@{ Name = 'settings.gradle.kts'; Text = $settingsText },
    [ordered]@{ Name = 'app/build.gradle.kts'; Text = $appBuildText }
)) {
    if ($gate.Text -notmatch 'dependencyVerificationMode\s*==\s*DependencyVerificationMode\.STRICT') {
        throw "$($gate.Name) does not contain the internal strict dependency-verification gate."
    }
}
foreach ($forbiddenGradleSigningText in @(
    'FITNESS_RELEASE_',
    'signingConfigs.create',
    'storePassword',
    'keyPassword',
    'KeyStore.getInstance'
)) {
    Assert-DoesNotContain $appBuildText $forbiddenGradleSigningText 'app/build.gradle.kts'
}
Assert-Contains $appBuildText 'signingConfig = null' 'app/build.gradle.kts'
Assert-Contains $appBuildText 'signingConfig == null' 'app/build.gradle.kts'
Assert-Contains $appBuildText 'releaseArtifactSigningMode") == "external-apksigner"' 'app/build.gradle.kts'
Assert-Contains $appBuildText 'releaseSignatureSchemes") == "v2-only"' 'app/build.gradle.kts'
Assert-Contains $appBuildText 'materialHashMode") == "git-blob-bytes-sha256"' 'app/build.gradle.kts'
Assert-Contains $appBuildText 'abiFilters += releaseAbis' 'app/build.gradle.kts'

$publishPath = Join-Path $repoRoot 'scripts\Publish-TaggedRelease.ps1'
$publishText = Get-Content -LiteralPath $publishPath -Raw -Encoding UTF8
Assert-Contains $publishText "Join-Path `$PSScriptRoot 'Invoke-GradleVerified.ps1'" 'Publish-TaggedRelease.ps1'
Assert-DoesNotContain $publishText 'Invoke-GradleWithReleaseSigning.ps1' 'Publish-TaggedRelease.ps1'
Assert-DoesNotContain $publishText 'apksigner.bat' 'Publish-TaggedRelease.ps1'
Assert-DoesNotContain $publishText "'pass:" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText '& $javaExecutable -jar $apksignerJar @signArguments' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText '--ks-pass' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText '"env:$storePasswordEnvironmentName"' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText '"env:$keyPasswordEnvironmentName"' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'gradleSigningEnvironmentCleared = $true' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'gradleProcessExitedBeforeCredentialResolution = $true' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'passwordValuesPresentInArgumentsOrEvidence = $false' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'apkSignedOutsideGradle = $true' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "materialHashMode = 'git-blob-bytes-sha256'" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "'cat-file', 'blob', `$objectId" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'StandardOutput.BaseStream' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'Assert-ReleaseTagSignature' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'Assert-ReleaseApkNativeLibraries' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'Invoke-ReleaseVerificationEvidence.ps1' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText 'Finalize-ReleaseEvidence.ps1' 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "'scripts/ProcessEnvironment.Common.ps1' = Get-GitBlobInfo" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "'scripts/Test-ProcessEnvironmentRestoration.ps1' = Get-GitBlobInfo" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "'scripts/GradleDistribution.Common.ps1' = Get-GitBlobInfo" 'Publish-TaggedRelease.ps1'
Assert-Contains $publishText "'scripts/Test-GradleDistributionSeeding.ps1' = Get-GitBlobInfo" 'Publish-TaggedRelease.ps1'

$deliveryGateText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\Test-DeliveryMetadata.ps1') -Raw -Encoding UTF8
foreach ($requiredDeliveryGateText in @(
    '[Parameter(Mandatory = $true)]',
    "[ValidatePattern('^[0-9a-f]{40}`$')]",
    'aapt2 dump badging',
    'verify -Werr --verbose --print-certs',
    'Verified using v2 scheme (APK Signature Scheme v2): true',
    'Number of signers: 1',
    '-c -P 16 -v 4',
    'Read-ApkProvenance',
    'git-blob-bytes-sha256',
    "Join-Path `$DeliveryDirectory '历史版本'",
    'Top-level SHA256SUMS.txt must contain exactly the current install APK line'
    'ReleaseProvenanceSignature.java'
    'Signed provenance manifest does not cover exactly every required evidence file.'
    'Assert-ReleaseTagSignature'
    'Assert-ReleaseApkNativeLibraries'
    'verified-local-archive-with-pinned-sha256'
    'dependencyCachesSeeded'
    'Assert-DeliveryApkPlacement'
)) {
    Assert-Contains $deliveryGateText $requiredDeliveryGateText 'Test-DeliveryMetadata.ps1'
}

$releaseEvidenceText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\Invoke-ReleaseVerificationEvidence.ps1') -Raw -Encoding UTF8
foreach ($requiredReleaseEvidenceText in @(
    ':app:testDebugUnitTest',
    ':app:testReleaseUnitTest',
    ':app:lintRelease',
    'Test-ReleaseDependencyVerificationGate.ps1',
    'Test-PowerShellParser.ps1',
    'release-verification-evidence.json',
    'jvmResults',
    'tool-versions.json',
    'exitCode = $exitCode',
    'versionExitCode',
    'executableSha256'
)) {
    Assert-Contains $releaseEvidenceText $requiredReleaseEvidenceText 'Invoke-ReleaseVerificationEvidence.ps1'
}

$finalizeText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\Finalize-ReleaseEvidence.ps1') -Raw -Encoding UTF8
foreach ($requiredFinalizeText in @(
    'delivery-gate-pre-signature.log',
    'app-release.apk.provenance-manifest.json',
    'app-release.apk.provenance-signature.bin',
    'ReleaseProvenanceSignature.java',
    'Assert-InstrumentationEvidenceBundle',
    'Assert-ReleaseVerificationEvidenceBundle',
    'Unprotect-ReleaseSigningPayload',
    '-PreSignatureCapture'
)) {
    Assert-Contains $finalizeText $requiredFinalizeText 'Finalize-ReleaseEvidence.ps1'
}

$instrumentationEvidenceText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\Invoke-InstrumentationWithEvidence.ps1') -Raw -Encoding UTF8
foreach ($requiredEvidenceText in @(
    "schema = 'fitness-ledger-instrumentation-evidence-v1'",
    "tasks = @('clean', ':app:connectedDebugAndroidTest')",
    'androidSerialWasExplicit = $true',
    "Assert-PathOutsideRepository `$RepoRoot `$OutputDirectory 'Instrumentation evidence output'",
    "Assert-PathOutsideRepository `$RepoRoot `$ReleaseApkPath 'ReleaseApkPath preserved copy'",
    'cleanAfterRun = $true',
    'ExpectedTestCount',
    'ExpectedRevision',
    'raw-junit',
    'tested-artifacts',
    'gradle-connected-debug-android-test.log',
    'directlyExercisedByThisRun = $false',
    "status = 'PASS'"
)) {
    Assert-Contains $instrumentationEvidenceText $requiredEvidenceText 'Invoke-InstrumentationWithEvidence.ps1'
}

$commonEvidenceText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\ReleaseSigning.Common.ps1') -Raw -Encoding UTF8
foreach ($requiredCommonEvidenceText in @(
    'Assert-ExactEvidenceChecksumFile',
    'Assert-ReleaseVerificationEvidenceBundle',
    'Assert-InstrumentationEvidenceBundle',
    'Release verification manifest must contain exactly the five required runs.',
    'Instrumentation JUnit totals do not match the signed PASS summary.',
    'Instrumentation device summary does not match device.properties'
)) {
    Assert-Contains $commonEvidenceText $requiredCommonEvidenceText 'ReleaseSigning.Common.ps1'
}

& (Join-Path $repoRoot 'scripts\Test-ReleaseEvidenceIntegrity.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Release evidence integrity negative tests failed.' }
& (Join-Path $repoRoot 'scripts\Test-ProcessEnvironmentRestoration.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Process environment restoration tests failed.' }
& (Join-Path $repoRoot 'scripts\Test-GradleDistributionSeeding.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Gradle distribution seeding tests failed.' }

$clearIndex = $publishText.IndexOf('# SECURITY ORDER: clear every known signing environment before Gradle starts.', [StringComparison]::Ordinal)
$gradleIndex = $publishText.IndexOf('& $verifiedGradle @gradleParameters', [StringComparison]::Ordinal)
$gradleExitIndex = $publishText.IndexOf('# SECURITY ORDER: Gradle has exited;', [StringComparison]::Ordinal)
$alignIndex = $publishText.IndexOf('$alignmentOutput = @(& $zipalign @alignArguments', [StringComparison]::Ordinal)
$credentialIndex = $publishText.IndexOf('# SECURITY ORDER: resolve credentials only after Gradle exit', [StringComparison]::Ordinal)
$signIndex = $publishText.IndexOf('& $javaExecutable -jar $apksignerJar @signArguments', [StringComparison]::Ordinal)
if ($clearIndex -lt 0 -or $gradleIndex -le $clearIndex -or $gradleExitIndex -le $gradleIndex -or
    $alignIndex -le $gradleExitIndex -or $credentialIndex -le $alignIndex -or $signIndex -le $credentialIndex) {
    throw 'Publish-TaggedRelease.ps1 does not enforce clear-env -> Gradle exit -> zipalign -> credential resolution -> external sign order.'
}

$disabledWrapperPath = Join-Path $repoRoot 'scripts\Invoke-GradleWithReleaseSigning.ps1'
$disabledWrapperText = Get-Content -LiteralPath $disabledWrapperPath -Raw -Encoding UTF8
Assert-Contains $disabledWrapperText 'HARD-DISABLED:' 'Invoke-GradleWithReleaseSigning.ps1'
foreach ($forbiddenDisabledWrapperText in @(
    'ReleaseSigning.Common.ps1',
    'Unprotect-ReleaseSigningPayload',
    'SetEnvironmentVariable',
    'Invoke-GradleVerified.ps1',
    'gradlew'
)) {
    Assert-DoesNotContain $disabledWrapperText $forbiddenDisabledWrapperText 'Invoke-GradleWithReleaseSigning.ps1'
}

$hostExecutable = (Get-Process -Id $PID).Path
$sentinel = "DO_NOT_LOG_RELEASE_SECRET_$([Guid]::NewGuid().ToString('N'))"
$testEnvironmentBefore = @{}
try {
    foreach ($name in $script:ReleaseSigningEnvironmentNames) {
        $testEnvironmentBefore[$name] = Get-FitnessProcessEnvironmentVariableState $name
        [Environment]::SetEnvironmentVariable($name, "$sentinel-$name", 'Process')
    }
    $disabledOutput = @(& $hostExecutable -NoProfile -File $disabledWrapperPath ':app:assembleRelease' 2>&1)
    $disabledExitCode = $LASTEXITCODE
} finally {
    foreach ($name in $script:ReleaseSigningEnvironmentNames) {
        Restore-FitnessProcessEnvironmentVariable $testEnvironmentBefore[$name]
    }
}
$disabledText = $disabledOutput -join [Environment]::NewLine
if ($disabledExitCode -eq 0 -or $disabledText -notmatch 'HARD-DISABLED:') {
    throw "Obsolete release-signing wrapper did not fail closed as expected:`n$disabledText"
}
if ($disabledText.Contains($sentinel)) {
    throw 'Obsolete release-signing wrapper exposed a signing-secret sentinel.'
}

$publishProbeSentinel = "DO_NOT_LOG_PUBLISH_SECRET_$([Guid]::NewGuid().ToString('N'))"
$escapedPublishPath = $publishPath.Replace("'", "''")
$publishEnvironmentProbe = @"
`$releaseNames = @('FITNESS_RELEASE_STORE_FILE','FITNESS_RELEASE_STORE_PASSWORD','FITNESS_RELEASE_KEY_ALIAS','FITNESS_RELEASE_KEY_PASSWORD')
`$temporaryNames = @('FITNESS_APKSIGNER_STORE_PASSWORD_TEMP','FITNESS_APKSIGNER_KEY_PASSWORD_TEMP')
`$allNames = @(`$releaseNames + `$temporaryNames)
foreach (`$name in `$allNames) {
    [Environment]::SetEnvironmentVariable(`$name, '$publishProbeSentinel-' + `$name, 'Process')
}
& '$escapedPublishPath' -Offline | Out-Null
if (`$LASTEXITCODE -eq 0) { throw 'Formal publisher unexpectedly accepted -Offline.' }
foreach (`$name in `$allNames) {
    `$expected = '$publishProbeSentinel-' + `$name
    `$actual = [Environment]::GetEnvironmentVariable(`$name, 'Process')
    if (`$actual -cne `$expected) { throw 'Publisher did not restore environment: ' + `$name }
}
exit 0
"@
$encodedPublishEnvironmentProbe = [Convert]::ToBase64String(
    [Text.Encoding]::Unicode.GetBytes($publishEnvironmentProbe)
)
$publishProbeOutput = @(& $hostExecutable -NoProfile -EncodedCommand $encodedPublishEnvironmentProbe 2>&1)
$publishProbeExitCode = $LASTEXITCODE
$publishProbeText = $publishProbeOutput -join [Environment]::NewLine
if ($publishProbeExitCode -ne 0 -or $publishProbeText -notmatch 'Formal releases require a fresh isolated Gradle state') {
    throw "Publisher environment isolation probe failed:`n$publishProbeText"
}
if ($publishProbeText.Contains($publishProbeSentinel)) {
    throw 'Publisher exposed a signing-secret sentinel in output.'
}

if (-not $SkipGradleProbe) {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or
        -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
        throw 'JAVA_HOME must point to a JDK before running the direct Gradle gate probes.'
    }
    $gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
    foreach ($mode in @('off', 'lenient')) {
        $probeOutput = @(& $gradleWrapper `
            ':app:assembleRelease' `
            "--dependency-verification=$mode" `
            '--no-configuration-cache' `
            '--no-build-cache' `
            '--no-daemon' `
            '--offline' `
            '--console=plain' 2>&1)
        $probeExitCode = $LASTEXITCODE
        $probeText = $probeOutput -join [Environment]::NewLine
        if ($probeExitCode -eq 0) {
            throw "Direct Gradle release probe unexpectedly accepted dependency-verification mode '$mode'."
        }
        if ($probeText -notmatch 'Release artifact tasks require strict Gradle dependency verification') {
            throw "Direct Gradle '$mode' probe failed for an unexpected reason:`n$probeText"
        }
    }

    $signingReportOutput = @(& $gradleWrapper `
        ':app:signingReport' `
        '--dependency-verification=strict' `
        '--no-configuration-cache' `
        '--no-build-cache' `
        '--no-daemon' `
        '--offline' `
        '--console=plain' 2>&1)
    $signingReportExitCode = $LASTEXITCODE
    $signingReportText = $signingReportOutput -join [Environment]::NewLine
    if ($signingReportExitCode -ne 0) {
        throw "Gradle signingReport probe failed:`n$signingReportText"
    }
    if ($signingReportText -notmatch '(?s)Variant:\s*release\s+Config:\s*null') {
        throw "Gradle release signing config is not null:`n$signingReportText"
    }
}

Write-Host 'PASS: Gradle release is unsigned, obsolete secret-in-Gradle wrapper is disabled, and external signing order is fail-closed.'
if (-not $SkipGradleProbe) {
    Write-Host 'PASS: direct Gradle rejected off/lenient verification and signingReport confirmed release Config: null.'
}
exit 0
