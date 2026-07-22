package custom.android.plugin.config

import java.net.URI

object GitUrlNormalizer {
    fun toHttpsRepositoryUrl(remoteUrl: String): String {
        val trimmed = remoteUrl.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        val scpStyle = Regex("^git@([^:]+):(.+?)(\\.git)?$").matchEntire(trimmed)
        if (scpStyle != null) {
            return "https://${scpStyle.groupValues[1]}/${scpStyle.groupValues[2].removeSuffix(".git")}"
        }
        val sshUri = Regex("^ssh://git@([^/]+)/(.+?)(\\.git)?$").matchEntire(trimmed)
        if (sshUri != null) {
            return "https://${sshUri.groupValues[1]}/${sshUri.groupValues[2].removeSuffix(".git")}"
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.removeSuffix(".git")
        }
        return ""
    }

    fun toScmConnection(repositoryUrl: String): String {
        val parts = parse(repositoryUrl) ?: return ""
        return "scm:git:https://${parts.host}/${parts.path}.git"
    }

    fun toScmDeveloperConnection(repositoryUrl: String): String {
        val parts = parse(repositoryUrl) ?: return ""
        return "scm:git:ssh://git@${parts.host}/${parts.path}.git"
    }

    private fun parse(repositoryUrl: String): Parts? {
        val normalized = toHttpsRepositoryUrl(repositoryUrl)
        if (normalized.isBlank()) {
            return null
        }
        return try {
            val uri = URI(normalized)
            val host = uri.host.orEmpty()
            val path = uri.path.trim('/').removeSuffix(".git")
            if (host.isBlank() || path.isBlank()) null else Parts(host, path)
        } catch (_: Exception) {
            null
        }
    }

    private data class Parts(val host: String, val path: String)
}
