package custom.android.plugin

import custom.android.plugin.config.GitSafetyChecker
import custom.android.plugin.config.GitHubActionsWorkflowWriter
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

open class RollbackCentralPublishSecretsTask : DefaultTask() {
    init {
        group = "customPlugin"
        description = "Delete configured Central Portal repository secrets and optionally remove generated workflow."
    }

    @TaskAction
    fun rollback() {
        val configFile = PublishConfigResolver.centralPublishConfigFile(project)
        GitSafetyChecker.ensureIgnored(project.rootDir, configFile)
        val config = PublishConfigResolver.loadCentralPublishProperties(project)
        val repo = config.githubRepo
        if (repo.isNotBlank()) {
            val gh = GitHubSecretClient(project.findProperty("ghExecutable")?.toString().orEmpty().ifBlank { "gh" })
            listOf(
                config.effectiveMavenCentralUsernameSecret,
                config.effectiveMavenCentralPasswordSecret,
                config.effectiveGpgKeySecret,
                config.effectiveSigningPasswordSecret,
                config.effectiveSigningKeyIdSecret
            ).forEach { secretName -> gh.deleteSecret(repo, secretName) }
        }
        if (project.findProperty("removeGeneratedWorkflow")?.toString()?.toBooleanLenientLocal() == true) {
            removeGeneratedWorkflow(config.workflowPath)
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
        if (file.exists() && file.readText().contains(GitHubActionsWorkflowWriter.GENERATED_MARKER)) {
            file.delete()
        }
    }
}

internal fun String?.toBooleanLenientLocal(): Boolean {
    return this?.equals("true", ignoreCase = true) == true ||
        this == "1" ||
        this?.equals("yes", ignoreCase = true) == true
}
