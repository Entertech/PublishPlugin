package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

data class PublishProviderState(
    val provider: String,
    val status: String,
    val fingerprint: String,
    val message: String = "",
    val updatedAt: String = Instant.now().toString()
)

class PublishExecutionStateStore(project: Project) {
    private val file = File(project.buildDir, "reports/publish/provider-state.json")

    fun read(): Map<String, PublishProviderState> {
        if (!file.isFile) return emptyMap()
        return Regex("""\{\s*"provider":\s*"([^"]+)",\s*"status":\s*"([^"]+)",\s*"fingerprint":\s*"([^"]*)",\s*"message":\s*"([^"]*)",\s*"updatedAt":\s*"([^"]+)"\s*}""")
            .findAll(file.readText())
            .map { match ->
                val state = PublishProviderState(
                    provider = unescape(match.groupValues[1]),
                    status = unescape(match.groupValues[2]),
                    fingerprint = unescape(match.groupValues[3]),
                    message = unescape(match.groupValues[4]),
                    updatedAt = unescape(match.groupValues[5])
                )
                state.provider to state
            }.toMap()
    }

    fun write(states: Map<String, PublishProviderState>) {
        file.parentFile.mkdirs()
        val json = states.values.sortedBy { it.provider }.joinToString(",\n") { state ->
            "    {\"provider\": \"${escape(state.provider)}\", \"status\": \"${escape(state.status)}\", " +
                "\"fingerprint\": \"${escape(state.fingerprint)}\", \"message\": \"${escape(state.message)}\", " +
                "\"updatedAt\": \"${escape(state.updatedAt)}\"}"
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText("{\n  \"providers\": [\n$json\n  ]\n}\n")
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun fingerprint(publications: List<PublishValidationPublication>, bundle: PreparedArtifactBundle?): String {
            val content = buildString {
                publications.sortedBy { "${it.groupId}:${it.artifactId}:${it.version}" }.forEach {
                    append(it.groupId).append(':').append(it.artifactId).append(':').append(it.version).append('\n')
                }
                bundle?.publications?.flatMap { it.files }?.sortedBy { it.path }?.forEach {
                    append(it.path).append(':').append(it.sha256).append('\n')
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
        private fun unescape(value: String): String = value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
