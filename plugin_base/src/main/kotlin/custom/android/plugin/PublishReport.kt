package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.time.Instant

object PublishReport {
    fun write(project: Project, result: PublishValidationResult, dryRun: Boolean) {
        val directory = File(project.buildDir, "reports/publish").apply { mkdirs() }
        val fields = linkedMapOf(
            "modulePath" to project.path,
            "mode" to result.mode,
            "repositoryName" to result.repositoryName,
            "repositoryUrl" to result.repositoryUrl,
            "dryRun" to dryRun,
            "generatedAt" to Instant.now().toString()
        )
        val publications = result.publications.joinToString(",\n") { publication ->
            "    {\n" +
                "      \"name\": \"${escape(publication.name)}\",\n" +
                "      \"groupId\": \"${escape(publication.groupId)}\",\n" +
                "      \"artifactId\": \"${escape(publication.artifactId)}\",\n" +
                "      \"version\": \"${escape(publication.version)}\"\n" +
                "    }"
        }
        val json = buildString {
            append("{\n")
            fields.entries.forEachIndexed { _, (key, value) ->
                append("  \"").append(key).append("\": ")
                when (value) {
                    is Boolean -> append(value)
                    else -> append("\"").append(escape(value.toString())).append("\"")
                }
                append(",\n")
            }
            append("  \"publications\": [\n")
            append(publications)
            append("\n  ]\n}\n")
        }
        File(directory, "publish-manifest.json").writeText(json)

        val markdown = buildString {
            appendLine("# Publish manifest")
            appendLine()
            appendLine("- Module: `${project.path}`")
            appendLine("- Mode: `${result.mode}`")
            appendLine("- Repository: `${result.repositoryName}`")
            if (result.repositoryUrl.isNotBlank()) appendLine("- Repository URL: ${result.repositoryUrl}")
            appendLine("- Dry run: `$dryRun`")
            appendLine()
            appendLine("## Publications")
            appendLine()
            result.publications.forEach {
                appendLine("- `${it.groupId}:${it.artifactId}:${it.version}` (${it.name})")
            }
        }
        File(directory, "publish-manifest.md").writeText(markdown)
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
