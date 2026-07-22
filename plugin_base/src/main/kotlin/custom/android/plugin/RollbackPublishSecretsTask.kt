package custom.android.plugin

import custom.android.plugin.config.GitSafetyChecker
import custom.android.plugin.config.GitHubActionsWorkflowWriter
import custom.android.plugin.config.GitUrlNormalizer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI

open class RollbackPublishSecretsTask : DefaultTask() {
    init {
        group = "customPlugin"
        description = "Delete configured publishing repository secrets and optionally remove generated workflow."
    }

    @TaskAction
    fun rollback() {
        val configFile = PublishConfigResolver.publishConfigFile(project)
        GitSafetyChecker.ensureIgnored(project.rootDir, configFile)
        val config = PublishConfigResolver.loadPublishProperties(project)
        val gh = GitHubSecretClient(project.findProperty("ghExecutable")?.toString().orEmpty().ifBlank { "gh" })
        val repo = config.githubRepo.ifBlank { inferGithubRepo(gh) }
        if (repo.isBlank()) {
            throw GradleException("publish.githubRepo is required when it cannot be inferred")
        }
        listOf(
            config.effectiveMavenCentralUsernameSecret,
            config.effectiveMavenCentralPasswordSecret,
            config.effectiveGpgKeySecret,
            config.effectiveSigningPasswordSecret,
            config.effectiveSigningKeyIdSecret
        ).forEach { secretName -> gh.deleteSecret(repo, secretName) }
        if (project.findProperty("removeGeneratedWorkflow")?.toString()?.toBooleanLenientLocal() == true) {
            workflowPathsToRemove(config.workflowPath).forEach { removeGeneratedWorkflow(it) }
        }
        if (project.findProperty("printHistoryRewriteGuide")?.toString()?.toBooleanLenientLocal() == true) {
            PluginLogUtil.printlnInfoInScreen("Run git filter-repo --path local.properties --invert-paths after rotating leaked secrets.")
        }
    }

    private fun removeGeneratedWorkflow(workflowPath: String) {
        if (workflowPath.isBlank()) {
            return
        }
        val file = project.rootProject.file(workflowPath)
        if (file.exists() && GitHubActionsWorkflowWriter.isGeneratedWorkflow(file.readText())) {
            file.delete()
        }
    }

    private fun inferGithubRepo(gh: GitHubSecretClient): String {
        return try {
            gh.currentRepository()
        } catch (_: GradleException) {
            inferGithubRepoFromGitOrigin()
        }
    }

    private fun inferGithubRepoFromGitOrigin(): String {
        val remoteUrl = readGitOriginUrl().ifBlank { return "" }
        val repositoryUrl = GitUrlNormalizer.toHttpsRepositoryUrl(remoteUrl).ifBlank { return "" }
        return try {
            val uri = URI(repositoryUrl)
            if (uri.host != "github.com") {
                ""
            } else {
                uri.path.trim('/').removeSuffix(".git")
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readGitOriginUrl(): String {
        val gitConfig = File(project.rootDir, ".git/config").takeIf { it.exists() } ?: return ""
        var inOrigin = false
        return gitConfig.readLines()
            .firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    inOrigin = trimmed == "[remote \"origin\"]"
                    null
                } else if (inOrigin && trimmed.startsWith("url =")) {
                    trimmed.substringAfter("url =").trim()
                } else {
                    null
                }
            }
            .orEmpty()
    }

    private fun workflowPathsToRemove(workflowPath: String): List<String> {
        if (workflowPath.isNotBlank()) {
            return listOf(workflowPath)
        }
        return listOf(
            GitHubActionsWorkflowWriter.defaultWorkflowPath(project.name),
            GitHubActionsWorkflowWriter.legacyDefaultWorkflowPath(project.name)
        )
    }
}

internal fun String?.toBooleanLenientLocal(): Boolean {
    return this?.equals("true", ignoreCase = true) == true ||
        this == "1" ||
        this?.equals("yes", ignoreCase = true) == true
}
