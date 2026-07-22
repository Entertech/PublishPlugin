package custom.android.plugin

import custom.android.plugin.config.PublishConfig
import org.gradle.api.GradleException
import java.io.ByteArrayInputStream
import java.io.File

class GpgKeyManager(
    private val gpgExecutable: String = "gpg"
) {
    fun generateKey(config: PublishConfig) {
        if (config.gpgName.isBlank() || config.gpgEmail.isBlank() || config.signingPassword.isBlank()) {
            throw GradleException("gpgGenerate=true requires gpgName, gpgEmail and signingPassword")
        }
        if (config.gpgKeyFile.isBlank()) {
            throw GradleException("gpgGenerate=true requires gpgKeyFile")
        }
        val keyFile = File(config.gpgKeyFile)
        keyFile.parentFile?.mkdirs()
        val batchFile = File.createTempFile("publish-gpg-", ".batch")
        try {
            batchFile.writeText(
                listOf(
                    "Key-Type: ${config.gpgKeyType.ifBlank { "RSA" }}",
                    "Key-Length: ${config.gpgKeyLength.ifBlank { "4096" }}",
                    "Name-Real: ${config.gpgName}",
                    "Name-Email: ${config.gpgEmail}",
                    config.gpgComment.takeIf { it.isNotBlank() }?.let { "Name-Comment: $it" }.orEmpty(),
                    "Expire-Date: ${config.gpgKeyExpire.ifBlank { "2y" }}",
                    "Passphrase: ${config.signingPassword}",
                    "%commit",
                    ""
                ).filter { it.isNotBlank() }.joinToString(System.lineSeparator())
            )
            run(listOf(gpgExecutable, "--batch", "--generate-key", batchFile.absolutePath))
            exportSecretKey(config.gpgEmail, config.signingPassword, keyFile)
        } finally {
            batchFile.delete()
        }
    }

    private fun exportSecretKey(email: String, passphrase: String, outputFile: File) {
        val process = ProcessBuilder(
            listOf(
                gpgExecutable,
                "--armor",
                "--batch",
                "--pinentry-mode",
                "loopback",
                "--passphrase-fd",
                "0",
                "--export-secret-keys",
                email
            )
        ).redirectErrorStream(true).start()
        process.outputStream.use { output ->
            ByteArrayInputStream(passphrase.toByteArray(Charsets.UTF_8)).copyTo(output)
        }
        val output = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("gpg export failed")
        }
        outputFile.writeBytes(output)
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Command failed: ${command.joinToString(" ")}\n$output")
        }
    }
}
