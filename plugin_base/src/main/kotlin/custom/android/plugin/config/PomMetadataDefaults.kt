package custom.android.plugin.config

import org.gradle.api.Project

object PomMetadataDefaults {
    const val ANDROID_LIBRARY_DESCRIPTION = "Android library published as a Maven artifact"
    const val GRADLE_PLUGIN_DESCRIPTION = "Gradle plugin published as a Maven artifact"

    fun defaultPomName(project: Project, artifactId: String): String {
        return artifactId.ifBlank { project.name }
    }

    fun defaultPomDescription(project: Project): String {
        return if (isGradlePluginModule(project)) {
            GRADLE_PLUGIN_DESCRIPTION
        } else {
            ANDROID_LIBRARY_DESCRIPTION
        }
    }

    private fun isGradlePluginModule(project: Project): Boolean {
        return project.plugins.hasPlugin("java-gradle-plugin") ||
            project.plugins.hasPlugin("org.gradle.kotlin.kotlin-dsl") ||
            project.plugins.hasPlugin("groovy")
    }
}
