package custom.android.plugin

import org.gradle.api.GradleException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class GitHubSecretClient(
    private val ghExecutable: String = "gh"
) {
    fun listSecretNames(repo: String): Set<String> {
        val result = run(listOf(ghExecutable, "secret", "list", "-R", repo, "--json", "name", "--jq", ".[].name"))
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setSecret(repo: String, secretName: String, value: String) {
        run(
            listOf(ghExecutable, "secret", "set", secretName, "-R", repo),
            ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
        )
    }

    fun setSecretFromFile(repo: String, secretName: String, file: File) {
        file.inputStream().use { input ->
            run(listOf(ghExecutable, "secret", "set", secretName, "-R", repo), input)
        }
    }

    fun deleteSecret(repo: String, secretName: String) {
        val result = runAllowFailure(listOf(ghExecutable, "secret", "delete", secretName, "-R", repo))
        if (result.exitCode != 0) {
            PluginLogUtil.printlnErrorInScreen("Skip deleting $secretName: ${result.output.trim()}")
        }
    }

    fun assertAuthenticated() {
        run(listOf(ghExecutable, "auth", "status"))
    }

    fun currentRepository(): String {
        return run(listOf(ghExecutable, "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"))
            .output
            .trim()
    }

    private fun run(command: List<String>, input: InputStream? = null): CommandResult {
        val result = runAllowFailure(command, input)
        if (result.exitCode != 0) {
            throw GradleException("Command failed: ${command.joinToString(" ")}\n${result.output}")
        }
        return result
    }

    private fun runAllowFailure(command: List<String>, input: InputStream? = null): CommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (input != null) {
            process.outputStream.use { output -> input.copyTo(output) }
        } else {
            process.outputStream.close()
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CommandResult(exitCode, output)
    }

    private data class CommandResult(val exitCode: Int, val output: String)
}
