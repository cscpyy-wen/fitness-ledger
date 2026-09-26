import org.gradle.api.artifacts.verification.DependencyVerificationMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Mirrors are an explicit last-resort fallback for restricted networks.
        if (providers.gradleProperty("fitness.enableRepositoryMirrors").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (providers.gradleProperty("fitness.enableRepositoryMirrors").orNull == "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

@Suppress("DEPRECATION")
val configurationCacheRequestedForRelease = gradle.startParameter.isConfigurationCacheRequested
val explicitReleaseArtifactTasks = setOf(
    "assemblerelease",
    "bundlerelease",
    "packagerelease",
    "build",
    "assemble",
    "bundle",
)
val explicitReleaseArtifactRequested = gradle.startParameter.taskNames.any { requested ->
    requested.substringAfterLast(':').lowercase() in explicitReleaseArtifactTasks
}
check(!configurationCacheRequestedForRelease || !explicitReleaseArtifactRequested) {
    "Release artifact tasks require --no-configuration-cache before project signing configuration is evaluated"
}
check(
    !explicitReleaseArtifactRequested ||
        gradle.startParameter.dependencyVerificationMode == DependencyVerificationMode.STRICT
) {
    "Release artifact tasks require strict Gradle dependency verification; off and lenient modes are forbidden"
}

rootProject.name = "FitnessLedger"
include(":app")
// Isolated, read-only connectivity experiment; never shares the main ledger UID.
include(":xiaomi-probe")
