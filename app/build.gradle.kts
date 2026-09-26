import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.verification.DependencyVerificationMode
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class GenerateBuildProvenanceTask : DefaultTask() {
    @get:Input
    abstract val revision: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val resolvedRevision = revision.get()
        check(resolvedRevision.matches(Regex("[0-9a-f]{40}"))) {
            "Git revision must be a full 40-character lowercase SHA-1"
        }
        val output = outputDirectory.file("build-provenance.properties").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "revision=$resolvedRevision\n" +
                "versionName=${versionName.get()}\n" +
                "versionCode=${versionCode.get()}\n",
            Charsets.UTF_8,
        )
    }
}

abstract class VerifyOnDeviceFoodAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetRoot: DirectoryProperty

    /** Each value is encoded as `<byte-length>|<uppercase-sha256>`. */
    @get:Input
    abstract val expectedAssets: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val expected = expectedAssets.get()
        expected.forEach { (relativePath, metadata) ->
            val (expectedLength, expectedSha256) = metadata.split('|', limit = 2)
            val file = assetRoot.file(relativePath).get().asFile
            check(file.isFile) { "Missing on-device food asset: $relativePath" }
            check(file.length() == expectedLength.toLong()) {
                "Unexpected byte length for $relativePath: ${file.length()}"
            }
            check(sha256(file) == expectedSha256) { "Unexpected SHA-256 for $relativePath" }
        }

        val labels = assetRoot.file("models/aiy_food_v1_labels.csv").get().asFile.readLines(Charsets.UTF_8)
        check(labels.size == 2_025 && labels.first() == "id,name") {
            "AIY label map must contain one header plus 2024 classes"
        }
        check(labels.drop(1).withIndex().all { (index, line) ->
            line.substringBefore(',').toIntOrNull() == index
        }) { "AIY label ids must be continuous from 0 through 2023" }

        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parse(
            assetRoot.file("models/aiy_food_v1_manifest.json").get().asFile,
        ) as Map<String, Any>
        check(manifest["modelSha256"] == shaFor(expected, "models/aiy_food_v1.tflite"))
        check(manifest["labelsSha256"] == shaFor(expected, "models/aiy_food_v1_labels.csv"))
        check(manifest["nutritionMapSha256"] == shaFor(expected, "models/aiy_food_v1_nutrition_map.json"))
        check(manifest["licenseSha256"] == shaFor(expected, "licenses/Apache-2.0.txt"))
        check(manifest["thirdPartyNoticeSha256"] == shaFor(expected, "licenses/LiteRT-2.2.0-THIRD_PARTY_NOTICE.txt"))
    }

    private fun shaFor(expected: Map<String, String>, path: String): String =
        expected.getValue(path).substringAfter('|')

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02X".format(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02X".format(it) }

fun sha256File(file: File): String = sha256Hex(file.readBytes())

val onDeviceFoodAssets = linkedMapOf(
    "models/aiy_food_v1.tflite" to (21_151_551L to "03DD6D9129501F97BE00775D9E17B5D9AD13BE730149352ADB8F4CA953B7A650"),
    "models/aiy_food_v1_labels.csv" to (34_210L to "8335571E32D893C986ADDAE14D96B6AB0157EA1F8BAD983A22C224E9EEAC6F90"),
    "models/aiy_food_v1_nutrition_map.json" to (4_123L to "3262EEB5A07E9F92DC474EB6231F97B5F6DBEBA93F7DECD46AF326DEEA7B151B"),
    "licenses/Apache-2.0.txt" to (13_575L to "71C6915D04265772A0339BED47276942C678B45CC01534210EBE6984FD1AEC65"),
    "licenses/LiteRT-2.2.0-THIRD_PARTY_NOTICE.txt" to (1_915_758L to "2D4D617FF3047813C1B4BFD66DC3C95B2352B401546C6E58A33B7C885091372A"),
)

val verifyOnDeviceFoodAssets = tasks.register<VerifyOnDeviceFoodAssetsTask>("verifyOnDeviceFoodAssets") {
    group = "verification"
    description = "Fails closed unless bundled food-model assets match the reviewed manifest."
    assetRoot.set(layout.projectDirectory.dir("src/main/assets"))
    expectedAssets.set(onDeviceFoodAssets.mapValues { (_, metadata) ->
        "${metadata.first}|${metadata.second}"
    })
}

val releasePolicyFile = rootProject.file("release-policy.properties")
check(releasePolicyFile.isFile) { "Missing release-policy.properties" }
val releasePolicy = Properties().apply {
    releasePolicyFile.inputStream().use(::load)
}
fun requiredReleasePolicy(name: String): String = releasePolicy.getProperty(name)
    ?.takeIf(String::isNotBlank)
    ?: error("Release policy field '$name' is missing")

check(requiredReleasePolicy("policyFormat") == "fitness-ledger-release-policy-v1") {
    "Unsupported release policy format"
}
val expectedReleaseCertificate = requiredReleasePolicy("signingCertificateSha256")
    .replace(Regex("[^0-9A-Fa-f]"), "")
    .uppercase()
check(expectedReleaseCertificate.matches(Regex("[0-9A-F]{64}"))) {
    "Release policy certificate SHA-256 must contain exactly 64 hexadecimal digits"
}
val releaseTagPrefix = requiredReleasePolicy("releaseTagPrefix")
check(releaseTagPrefix == "v") { "Only the canonical v-prefixed release tag policy is supported" }
check(requiredReleasePolicy("requireNoBuildCache").toBooleanStrict()) {
    "Release policy must require --no-build-cache"
}
check(requiredReleasePolicy("requireNoConfigurationCache").toBooleanStrict()) {
    "Release policy must require --no-configuration-cache"
}
check(requiredReleasePolicy("requireStrictDependencyVerification").toBooleanStrict()) {
    "Release policy must require strict Gradle dependency verification"
}
check(requiredReleasePolicy("releaseArtifactSigningMode") == "external-apksigner") {
    "Release policy must require external apksigner signing"
}
check(requiredReleasePolicy("releaseSignatureSchemes") == "v2-only") {
    "Release policy must require v2-only APK signing"
}
check(requiredReleasePolicy("materialHashMode") == "git-blob-bytes-sha256") {
    "Release policy must hash source materials from exact Git blob bytes"
}
check(requiredReleasePolicy("minSdk").toInt() == 28) {
    "Release policy minSdk must match the Android configuration"
}
check(requiredReleasePolicy("targetSdk").toInt() == 36) {
    "Release policy targetSdk must match the Android configuration"
}
val releaseAbis = requiredReleasePolicy("releaseAbis").split(',')
check(releaseAbis == listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
    "Release ABI policy must exclude unsupported 32-bit x86 and retain the three LiteRT ABIs"
}
val requiredNativeLibraries = requiredReleasePolicy("requiredNativeLibraries").split(',')
check(requiredNativeLibraries == listOf("liblitert_jni.so", "libLiteRt.so", "libLiteRtClGlAccelerator.so")) {
    "Release native-library policy must pin the complete LiteRT runtime set"
}

@Suppress("DEPRECATION")
val releaseConfigurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested

fun gitText(vararg arguments: String): String = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", *arguments)
}.standardOutput.asText.get().trim()

// Gradle release tasks deliberately produce unsigned artifacts. The formal
// publisher signs only after this Gradle process has exited, so no Gradle or
// Kotlin daemon ever receives keystore passwords.
val releaseArtifactTaskNames = setOf(
    "assembleRelease",
    "packageRelease",
    "bundleRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
)
gradle.taskGraph.whenReady {
    val includesReleaseArtifact = allTasks.any { task ->
        task.project == project && (
            task.name in releaseArtifactTaskNames ||
                (task.name.startsWith("publish") && task.name.contains("Release"))
            )
    }
    if (includesReleaseArtifact) {
        check(!gradle.startParameter.isBuildCacheEnabled) {
            "Release artifact tasks require --no-build-cache"
        }
        check(!releaseConfigurationCacheRequested) {
            "Release artifact tasks require --no-configuration-cache so signing secrets are not serialized"
        }
        check(gradle.startParameter.dependencyVerificationMode == DependencyVerificationMode.STRICT) {
            "Release artifact tasks require strict Gradle dependency verification; off and lenient modes are forbidden"
        }
        check(android.buildTypes.getByName("release").signingConfig == null) {
            "Gradle release artifacts must remain unsigned; signing is external to Gradle"
        }
        val status = gitText("status", "--porcelain=v1", "--untracked-files=all")
        check(status.isBlank()) {
            "Release artifact tasks require a clean Git HEAD; dirty entries are present"
        }
        val headRevision = gitText("rev-parse", "HEAD")
        check(headRevision.matches(Regex("[0-9a-f]{40}"))) { "Unexpected Git HEAD revision" }

        val headTags = gitText("tag", "--points-at", "HEAD")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        check(headTags.size == 1) {
            "Release artifact tasks require exactly one tag at HEAD; found ${headTags.size}"
        }
        val expectedTag = "$releaseTagPrefix${android.defaultConfig.versionName}"
        check(headTags.single() == expectedTag) {
            "The unique HEAD tag '${headTags.single()}' must equal versionName tag '$expectedTag'"
        }
        if (requiredReleasePolicy("requireAnnotatedTag").toBooleanStrict()) {
            check(gitText("cat-file", "-t", "refs/tags/$expectedTag") == "tag") {
                "Release tag '$expectedTag' must be an annotated tag object"
            }
        }
        check(gitText("rev-list", "-n", "1", expectedTag) == headRevision) {
            "Release tag '$expectedTag' does not resolve to HEAD"
        }
    }
}

android {
    namespace = "com.personal.fitnessledger"
    sourceSets.getByName("main").java.srcDir("../xiaomi-cloud-core/src/main/kotlin")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.fitnessledger"
        minSdk = 28
        targetSdk = 36
        versionCode = 28
        versionName = "0.6.0-alpha11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += releaseAbis
        }
    }

    buildTypes {
        release {
            signingConfig = null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOnDeviceFoodAssets)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val buildRevision = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val buildVersionName = requireNotNull(android.defaultConfig.versionName)
val buildVersionCode = android.defaultConfig.versionCode ?: error("versionCode is required")
val buildProvenanceDirectory = layout.buildDirectory.dir("generated/build-provenance/assets")
val generateBuildProvenance = tasks.register<GenerateBuildProvenanceTask>("generateBuildProvenance") {
    revision.set(buildRevision)
    versionName.set(buildVersionName)
    versionCode.set(buildVersionCode.toString())
    outputDirectory.set(buildProvenanceDirectory)
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateBuildProvenance,
            GenerateBuildProvenanceTask::outputDirectory,
        )
    }
}

val releaseSbomFile = layout.buildDirectory.file("reports/sbom/release-runtime.cdx.json")
tasks.register("generateReleaseSbom") {
    group = "verification"
    description = "Generates a CycloneDX inventory of exact release runtime artifacts and hashes."
    inputs.file(releasePolicyFile)
    inputs.property("revision", buildRevision)
    inputs.property("versionName", buildVersionName)
    inputs.property("versionCode", buildVersionCode)
    outputs.file(releaseSbomFile)
    outputs.upToDateWhen { false }

    doLast {
        val runtimeComponents = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .groupBy { artifact ->
                val id = artifact.moduleVersion.id
                Triple(id.group, id.name, id.version)
            }
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))
            .map { (coordinate, artifacts) ->
                val (group, name, version) = coordinate
                val purl = "pkg:maven/$group/$name@$version"
                val uniqueArtifacts = artifacts
                    .distinctBy { it.file.canonicalFile }
                    .sortedBy { it.file.name }
                linkedMapOf<String, Any>(
                    "type" to "library",
                    "bom-ref" to purl,
                    "purl" to purl,
                    "group" to group,
                    "name" to name,
                    "version" to version,
                    "scope" to "required",
                    "hashes" to uniqueArtifacts.map { artifact ->
                        linkedMapOf<String, Any>(
                            "alg" to "SHA-256",
                            "content" to sha256File(artifact.file),
                        )
                    },
                    "properties" to uniqueArtifacts.map { artifact ->
                        linkedMapOf<String, Any>(
                            "name" to "fitness.resolvedArtifact",
                            "value" to artifact.file.name,
                        )
                    },
                )
            }
        val modelAssetRoot = layout.projectDirectory.dir("src/main/assets")
        val assetComponents = listOf(
            linkedMapOf<String, Any>(
                "type" to "machine-learning-model",
                "bom-ref" to "pkg:generic/google-aiy-food-v1@1",
                "purl" to "pkg:generic/google-aiy-food-v1@1",
                "name" to "Google AIY Food V1",
                "version" to "1",
                "hashes" to listOf(linkedMapOf("alg" to "SHA-256", "content" to onDeviceFoodAssets.getValue("models/aiy_food_v1.tflite").second)),
                "licenses" to listOf(linkedMapOf("license" to linkedMapOf("id" to "Apache-2.0"))),
                "properties" to listOf(
                    linkedMapOf("name" to "fitness.assetPath", "value" to "models/aiy_food_v1.tflite"),
                    linkedMapOf("name" to "fitness.assetBytes", "value" to modelAssetRoot.file("models/aiy_food_v1.tflite").asFile.length().toString()),
                ),
            ),
            linkedMapOf<String, Any>(
                "type" to "data",
                "bom-ref" to "pkg:generic/google-aiy-food-v1-labels@1",
                "purl" to "pkg:generic/google-aiy-food-v1-labels@1",
                "name" to "Google AIY Food V1 labels",
                "version" to "1",
                "hashes" to listOf(linkedMapOf("alg" to "SHA-256", "content" to onDeviceFoodAssets.getValue("models/aiy_food_v1_labels.csv").second)),
                "licenses" to listOf(linkedMapOf("license" to linkedMapOf("id" to "Apache-2.0"))),
            ),
            linkedMapOf<String, Any>(
                "type" to "data",
                "bom-ref" to "pkg:generic/usda-fndds-fitness-map@2024-10-31",
                "purl" to "pkg:generic/usda-fndds-fitness-map@2024-10-31",
                "name" to "Reviewed USDA FNDDS fitness mapping",
                "version" to "2024-10-31",
                "hashes" to listOf(linkedMapOf("alg" to "SHA-256", "content" to onDeviceFoodAssets.getValue("models/aiy_food_v1_nutrition_map.json").second)),
                "licenses" to listOf(linkedMapOf("license" to linkedMapOf("name" to "US Government public domain"))),
            ),
        )
        val revision = buildRevision.get()
        val sourceComponents = listOf(
            linkedMapOf<String, Any>(
                "type" to "library",
                "bom-ref" to "pkg:generic/fitness-xiaomi-cloud-core@$revision",
                "purl" to "pkg:generic/fitness-xiaomi-cloud-core@$revision",
                "name" to "Fitness Xiaomi cloud shared protocol source",
                "version" to revision,
                "scope" to "required",
                "licenses" to listOf(linkedMapOf("license" to linkedMapOf("id" to "GPL-3.0-or-later"))),
                "properties" to listOf(
                    linkedMapOf("name" to "fitness.sourcePath", "value" to "xiaomi-cloud-core/src/main/kotlin"),
                    linkedMapOf("name" to "fitness.git.revision", "value" to revision),
                    linkedMapOf("name" to "fitness.noticePath", "value" to "assets/licenses/Xiaomi-cloud-NOTICE.txt"),
                ),
            ),
        )
        val components = runtimeComponents + assetComponents + sourceComponents

        val serial = UUID.nameUUIDFromBytes(
            "$revision:$buildVersionName:$buildVersionCode".toByteArray(Charsets.UTF_8),
        )
        val applicationPurl = "pkg:apk/com.personal.fitnessledger@$buildVersionName"
        val componentRefs = components.map { it.getValue("bom-ref") as String }
        val bom = linkedMapOf<String, Any>(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.6",
            "serialNumber" to "urn:uuid:$serial",
            "version" to 1,
            "metadata" to linkedMapOf<String, Any>(
                "timestamp" to Instant.now().toString(),
                "tools" to linkedMapOf<String, Any>(
                    "components" to listOf(
                        linkedMapOf<String, Any>(
                            "type" to "application",
                            "name" to "Fitness Ledger Gradle SBOM task",
                            "version" to "1",
                        ),
                    ),
                ),
                "component" to linkedMapOf<String, Any>(
                    "type" to "application",
                    "bom-ref" to applicationPurl,
                    "purl" to applicationPurl,
                    "group" to "com.personal",
                    "name" to "fitnessledger",
                    "version" to buildVersionName,
                    "properties" to listOf(
                        linkedMapOf("name" to "fitness.git.revision", "value" to revision),
                        linkedMapOf("name" to "fitness.versionCode", "value" to buildVersionCode.toString()),
                        linkedMapOf("name" to "fitness.releaseTag", "value" to "$releaseTagPrefix$buildVersionName"),
                        linkedMapOf("name" to "fitness.expectedSigningCertificateSha256", "value" to expectedReleaseCertificate),
                        linkedMapOf("name" to "fitness.releaseArtifactSigningMode", "value" to "external-apksigner"),
                        linkedMapOf("name" to "fitness.dependencyConfiguration", "value" to "releaseRuntimeClasspath"),
                        linkedMapOf("name" to "fitness.dependencyGraphSemantics", "value" to "flattened resolved runtime"),
                    ),
                ),
            ),
            "components" to components,
            "dependencies" to listOf(
                linkedMapOf<String, Any>("ref" to applicationPurl, "dependsOn" to componentRefs),
            ) + componentRefs.map { ref -> linkedMapOf<String, Any>("ref" to ref, "dependsOn" to emptyList<String>()) },
        )
        val output = releaseSbomFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(bom)), Charsets.UTF_8)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("com.google.ai.edge.litert:litert:2.2.0") {
        // The model is bundled in the APK and uses only the CPU Interpreter.
        // Excluding AI Pack delivery avoids Play Asset Delivery, WorkManager,
        // boot receivers, background services, and their extra permissions.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
