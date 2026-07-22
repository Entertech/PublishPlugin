package custom.android.plugin.config

import java.io.File

object GitSafetyChecker {
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
}
