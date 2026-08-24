package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.util.Properties

class PublishRuntimeConfig(project: Project) {
    val properties: Properties = Properties()

    init {
        if (System.getenv("GITHUB_ACTIONS")?.equals("true", ignoreCase = true) != true) {
            val configured = project.findProperty("publishLocalConfig")?.toString().orEmpty()
            val file = if (configured.isBlank()) {
                project.rootProject.file(".publish/local.properties")
            } else {
                val candidate = File(configured)
                if (candidate.isAbsolute) candidate else project.rootProject.file(configured)
            }
            if (file.isFile) file.inputStream().use { properties.load(it) }
        }
    }

    fun value(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        properties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
    }.orEmpty()
}
