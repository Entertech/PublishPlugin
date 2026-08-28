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
            "validationLevel" to result.validationLevel.name.lowercase(),
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
            append("\n  ],\n")
            appendStringMap("credentialSources", result.credentialSources)
            append(",\n")
            appendObjectList("preflightResults", result.preflightResults.map {
                linkedMapOf<String, Any>(
                    "provider" to it.provider,
                    "status" to it.status,
                    "message" to it.message,
                    "retryable" to it.retryable
                )
            })
            append(",\n")
            appendObjectList("providerResults", result.providerResults.map {
                linkedMapOf<String, Any>("provider" to it.provider, "status" to it.status, "message" to it.message)
            })
            append(",\n")
            appendObjectList("gates", result.gates.map {
                linkedMapOf<String, Any>("name" to it.name, "status" to it.status, "message" to it.message)
            })
            append(",\n")
            appendStringMap("provenance", result.provenance)
            append("\n}\n")
        }
        File(directory, "publish-manifest.json").writeText(json)

        val markdown = buildString {
            appendLine("# Publish manifest")
            appendLine()
            appendLine("- Module: `${project.path}`")
            appendLine("- Mode: `${result.mode}`")
            appendLine("- Repository: `${result.repositoryName}`")
            if (result.repositoryUrl.isNotBlank()) appendLine("- Repository URL: ${result.repositoryUrl}")
            appendLine("- Validation level: `${result.validationLevel.name.lowercase()}`")
            appendLine("- Dry run: `$dryRun`")
            appendLine()
            appendLine("## Publications")
            appendLine()
            result.publications.forEach {
                appendLine("- `${it.groupId}:${it.artifactId}:${it.version}` (${it.name})")
            }
            if (result.credentialSources.isNotEmpty()) {
                appendLine()
                appendLine("## Credential sources")
                appendLine()
                result.credentialSources.forEach { (name, source) -> appendLine("- `$name`: `$source`") }
            }
            if (result.preflightResults.isNotEmpty()) {
                appendLine()
                appendLine("## Preflight")
                appendLine()
                result.preflightResults.forEach { appendLine("- `${it.provider}`: ${it.status} — ${it.message}") }
            }
            if (result.providerResults.isNotEmpty()) {
                appendLine()
                appendLine("## Providers")
                appendLine()
                result.providerResults.forEach { appendLine("- `${it.provider}`: ${it.status} — ${it.message}") }
            }
            if (result.gates.isNotEmpty()) {
                appendLine()
                appendLine("## Gates")
                appendLine()
                result.gates.forEach { appendLine("- `${it.name}`: ${it.status} — ${it.message}") }
            }
            if (result.provenance.isNotEmpty()) {
                appendLine()
                appendLine("## Provenance")
                appendLine()
                result.provenance.forEach { (name, value) -> appendLine("- `$name`: `$value`") }
                appendLine("- SBOM: `publish-sbom.cdx.json`")
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

    private fun StringBuilder.appendStringMap(name: String, values: Map<String, String>) {
        append("  \"").append(name).append("\": {")
        if (values.isNotEmpty()) {
            append("\n")
            values.entries.forEachIndexed { index, entry ->
                append("    \"").append(escape(entry.key)).append("\": \"")
                    .append(escape(entry.value)).append("\"")
                if (index < values.size - 1) append(",")
                append("\n")
            }
            append("  ")
        }
        append("}")
    }

    private fun StringBuilder.appendObjectList(name: String, values: List<Map<String, Any>>) {
        append("  \"").append(name).append("\": [")
        if (values.isNotEmpty()) {
            append("\n")
            values.forEachIndexed { index, item ->
                append("    {")
                item.entries.forEachIndexed { fieldIndex, entry ->
                    if (fieldIndex == 0) append("\n")
                    append("      \"").append(escape(entry.key)).append("\": ")
                    when (val value = entry.value) {
                        is Boolean -> append(value)
                        else -> append("\"").append(escape(value.toString())).append("\"")
                    }
                    if (fieldIndex < item.size - 1) append(",")
                    append("\n")
                }
                append("    }")
                if (index < values.size - 1) append(",")
                append("\n")
            }
            append("  ")
        }
        append("]")
    }
}
