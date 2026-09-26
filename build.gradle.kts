import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

tasks.register("resolveDependenciesForVerification") {
    group = "verification"
    description = "Resolves all resolvable project configurations for Gradle verification metadata regeneration."
    doLast {
        allprojects.forEach { targetProject ->
            targetProject.configurations
                .filter { it.isCanBeResolved }
                .sortedBy { it.name }
                .forEach { configuration ->
                    logger.lifecycle("Resolving ${targetProject.path}:${configuration.name}")
                    configuration.incoming.artifactView {
                        componentFilter { component -> component !is ProjectComponentIdentifier }
                    }.files.files
                }
        }
    }
}
