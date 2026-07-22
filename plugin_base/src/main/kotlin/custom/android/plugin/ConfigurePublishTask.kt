package custom.android.plugin

import custom.android.plugin.config.GitHubActionsWorkflowWriter
import custom.android.plugin.config.GitSafetyChecker
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File

open class ConfigurePublishTask : DefaultTask() {
    init {
        group = "customPlugin"
        description = "Validate PublishInfo and configure GitHub secrets/workflow for publishing."
    }

    @TaskAction
    fun configure() {
        val configFile = PublishConfigResolver.publishConfigFile(project)
        if (!configFile.exists()) {
            throw GradleException("Missing ${configFile.name}. Run :${project.name}:generatePublishConfig first.")
        }
        val config = PublishConfigResolver.loadPublishProperties(project)
        val publishTarget = PublishConfigResolver.resolveWorkflowPublishTarget(project, config)
        val centralTarget = PublishConfigResolver.requiresCentralForWorkflowTarget(publishTarget)
        if (GitSafetyChecker.isTracked(project.rootDir, configFile)) {
            throw GradleException("${configFile.name} is tracked by git. Run: git rm --cached ${configFile.name}")
        }
        if (config.dryRunEnabled) {
            PluginLogUtil.printlnInfoInScreen("Dry run: would ensure ${configFile.name} is ignored by git.")
        } else {
            GitSafetyChecker.ensureIgnored(project.rootDir, configFile)
        }

        val publishInfo = project.extensions.findByType(PublishInfo::class.java)
            ?: throw GradleException("Current module does not apply cn.entertech.publish")
        validatePublishInfo(publishInfo)

        val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
        val publishingType = PublishConfigResolver.resolveCentralPublishingType(project, publishInfo)
        if (centralTarget) {
            if (publishInfo.groupId != namespace && !publishInfo.groupId.startsWith("$namespace.")) {
                throw GradleException("PublishInfo.groupId(${publishInfo.groupId}) must be under centralNamespace($namespace)")
            }
            if (publishingType != "user_managed" && publishingType != "automatic") {
                throw GradleException("centralPublishingType only supports user_managed or automatic")
            }
            val pomUrl = PublishConfigResolver.resolvePomUrl(project, publishInfo)
            if (pomUrl.isBlank()) {
                throw GradleException("pomUrl cannot be inferred. Configure it in PublishInfo.")
            }
        }

        if (config.githubSecretsEnabled && centralTarget) {
            if (config.dryRunEnabled) {
                printDryRunGithubSecrets(config)
            } else {
                configureGithubSecrets(config)
            }
        } else if (config.githubSecretsEnabled) {
            PluginLogUtil.printlnInfoInScreen("Skipping Central repository secrets because publishTarget is $publishTarget.")
        }
        val githubActions = project.findProperty("githubActions")?.toString()?.toBooleanLenientLocal()
            ?: config.githubActionsEnabled
        if (githubActions) {
            val workflowPath = project.findProperty("workflowPath")?.toString().orEmpty().ifBlank { config.workflowPath }
            val workflowUses = project.findProperty("workflowUses")?.toString().orEmpty().ifBlank { config.workflowUses }
            val githubPackagesRepository = project.findProperty("githubPackagesRepository")?.toString().orEmpty()
                .ifBlank { config.githubPackagesRepository }
            val githubPackagesUrl = project.findProperty("githubPackagesUrl")?.toString().orEmpty()
                .ifBlank { config.githubPackagesUrl }
            if (config.dryRunEnabled) {
                PluginLogUtil.printlnInfoInScreen("Dry run: would generate workflow for ${project.path}")
            } else {
                val workflowFile = GitHubActionsWorkflowWriter.writeWorkflow(
                    project.rootDir,
                    project.path,
                    project.name,
                    publishTarget,
                    namespace,
                    publishingType,
                    githubPackagesRepository,
                    githubPackagesUrl,
                    workflowPath,
                    workflowUses,
                    project.findProperty("overwriteWorkflow")?.toString()?.toBooleanLenientLocal() == true
                )
                PluginLogUtil.printlnInfoInScreen("Generated workflow: ${workflowFile.absolutePath}")
            }
        }
        if (centralTarget && publishingType == "user_managed") {
            PluginLogUtil.printlnInfoInScreen("Central publishing type is user_managed; publish manually in Central Portal after upload validation.")
        }
    }

    private fun printDryRunGithubSecrets(config: custom.android.plugin.config.PublishConfig) {
        val repo = config.githubRepo.ifBlank { "<inferred repository>" }
        val secretNames = listOf(
            config.effectiveMavenCentralUsernameSecret,
            config.effectiveMavenCentralPasswordSecret,
            config.effectiveGpgKeySecret,
            config.effectiveSigningPasswordSecret,
            config.effectiveSigningKeyIdSecret
        )
        PluginLogUtil.printlnInfoInScreen(
            "Dry run: would configure repository secrets in $repo: ${secretNames.joinToString()}"
        )
    }

    private fun validatePublishInfo(publishInfo: PublishInfo) {
        if (publishInfo.groupId.isBlank()) {
            throw GradleException("PublishInfo.groupId is required")
        }
        if (publishInfo.artifactId.isBlank()) {
            throw GradleException("PublishInfo.artifactId is required")
        }
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        if (version.isBlank()) {
            throw GradleException("PublishInfo.version is required")
        }
        if (version.contains("debug", ignoreCase = true)) {
            throw GradleException("$version contains debug")
        }
        if (isGradlePluginModule()) {
            if (publishInfo.pluginId.isBlank()) {
                throw GradleException("PublishInfo.pluginId is required for Gradle Plugin modules")
            }
            if (publishInfo.implementationClass.isBlank()) {
                throw GradleException("PublishInfo.implementationClass is required for Gradle Plugin modules")
            }
        }
    }

    private fun isGradlePluginModule(): Boolean {
        return project.plugins.hasPlugin("java-gradle-plugin") ||
            project.plugins.hasPlugin("org.gradle.kotlin.kotlin-dsl") ||
            project.plugins.hasPlugin("groovy")
    }

    private fun configureGithubSecrets(config: custom.android.plugin.config.PublishConfig) {
        val gh = GitHubSecretClient(project.findProperty("ghExecutable")?.toString().orEmpty().ifBlank { "gh" })
        gh.assertAuthenticated()
        val repo = config.githubRepo.ifBlank { gh.currentRepository() }
        if (repo.isBlank()) {
            throw GradleException("publish.githubRepo is required when it cannot be inferred")
        }
        val existingSecrets = gh.listSecretNames(repo)
        val overwrite = config.overwriteGithubSecretsEnabled
        setSecretIfNeeded(gh, repo, config.effectiveMavenCentralUsernameSecret, config.mavenCentralUsername, existingSecrets, overwrite)
        setSecretIfNeeded(gh, repo, config.effectiveMavenCentralPasswordSecret, config.mavenCentralPassword, existingSecrets, overwrite)

        if (config.gpgGenerateEnabled) {
            GpgKeyManager(project.findProperty("gpgExecutable")?.toString().orEmpty().ifBlank { "gpg" }).generateKey(config)
        }
        val shouldWriteGpgKey = overwrite || config.gpgGenerateEnabled || config.effectiveGpgKeySecret !in existingSecrets
        val shouldWriteSigningPassword =
            overwrite || config.gpgGenerateEnabled || config.effectiveSigningPasswordSecret !in existingSecrets
        val shouldWriteSigningKeyId = config.signingKeyId.isNotBlank() &&
            (overwrite || config.gpgGenerateEnabled || config.effectiveSigningKeyIdSecret !in existingSecrets)
        if (shouldWriteGpgKey || shouldWriteSigningPassword) {
            if (config.gpgKeyFile.isBlank() || config.signingPassword.isBlank()) {
                throw GradleException("GPG secrets require publish.gpgKeyFile and publish.signingPassword")
            }
        }
        if (shouldWriteGpgKey) {
            gh.setSecretFromFile(repo, config.effectiveGpgKeySecret, File(config.gpgKeyFile))
        }
        if (shouldWriteSigningPassword) {
            gh.setSecret(repo, config.effectiveSigningPasswordSecret, config.signingPassword)
        }
        if (shouldWriteSigningKeyId) {
            gh.setSecret(repo, config.effectiveSigningKeyIdSecret, config.signingKeyId)
        }
    }

    private fun setSecretIfNeeded(
        gh: GitHubSecretClient,
        repo: String,
        secretName: String,
        value: String,
        existingSecrets: Set<String>,
        overwrite: Boolean
    ) {
        if (secretName in existingSecrets && !overwrite) {
            return
        }
        if (value.isBlank()) {
            throw GradleException("Missing value for repository secret $secretName")
        }
        gh.setSecret(repo, secretName, value)
    }
}
