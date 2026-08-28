package custom.android.plugin.config

import java.io.File

object GitSafetyChecker {
    private val sensitiveKeys = listOf(
        "publish.mavenCentralPassword",
        "publish.signingPassword",
        "publish.gpgKeyFile",
        "GPG_KEY_CONTENTS",
        "SIGNING_PASSWORD"
    )

    fun isTracked(rootDir: File, configFile: File): Boolean {
        if (!File(rootDir, ".git").exists()) {
            return false
        }
        val relativePath = relativePath(rootDir, configFile)
        val process = ProcessBuilder("git", "ls-files", "--error-unmatch", relativePath)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        return process.waitFor() == 0
    }

    fun ensureIgnored(rootDir: File, configFile: File) {
        val relativePath = relativePath(rootDir, configFile)
        val gitignore = File(rootDir, ".gitignore")
        val existing = if (gitignore.exists()) gitignore.readText() else ""
        val patterns = existing.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toSet()
        if (relativePath in patterns || "/$relativePath" in patterns) {
            return
        }
        val separator = if (existing.isBlank() || existing.endsWith("\n")) "" else System.lineSeparator()
        gitignore.writeText(existing + separator + relativePath + System.lineSeparator())
    }

    fun relativePath(rootDir: File, configFile: File): String {
        return configFile.canonicalFile.relativeTo(rootDir.canonicalFile).invariantSeparatorsPath
    }

    fun findTrackedSensitiveKeys(rootDir: File): List<String> {
        if (!File(rootDir, ".git").exists()) return emptyList()
        val process = ProcessBuilder("git", "ls-files", "-z")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes()
        if (process.waitFor() != 0) return emptyList()
        val hits = mutableListOf<String>()
        output.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotBlank() }.forEach { path ->
            val file = File(rootDir, path)
            if (!file.isFile || file.length() > 5 * 1024 * 1024) return@forEach
            val content = runCatching { file.readText() }.getOrDefault("")
            sensitiveKeys.filter { key -> content.contains(key, ignoreCase = true) }
                .forEach { key -> hits += "$path contains $key" }
        }
        return hits
    }
}
