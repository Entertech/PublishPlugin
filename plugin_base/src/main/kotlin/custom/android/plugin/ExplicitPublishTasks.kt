package custom.android.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExplicitPublishTarget {
    LOCAL,
    GITHUB_PACKAGES,
    CENTRAL,
    ALL
}

open class ExplicitPublishTask : DefaultTask() {
    @get:Input
    var target: ExplicitPublishTarget = ExplicitPublishTarget.LOCAL

    @get:Input
    var componentKind: PublishComponentKind = PublishComponentKind.LIBRARY

    init {
        group = "customPlugin"
    }

    @TaskAction
    fun publish() {
        val source = ArtifactSource.parse(
            project.findProperty("artifactSource")?.toString()
                ?: System.getenv("PUBLISH_ARTIFACT_SOURCE")
        )
        val publishInfo = project.extensions.findByType(PublishInfo::class.java)
            ?: throw GradleException("PublishInfo is required for ${componentKind.taskNamePart} publishing")
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        val validation = PublishValidation.validateRemote(project, publishInfo, target, source)
        validation.warnings.forEach { PluginLogUtil.printlnInfoInScreen("WARNING: $it") }
        if (!validation.valid) {
            validation.errors.forEach { PluginLogUtil.printlnErrorInScreen(it) }
            throw GradleException("发布配置校验失败")
        }
        if (source == ArtifactSource.PREBUILT) {
            publishPrebuilt(publishInfo, version)
        } else {
            publishProject(publishInfo)
        }
        PublishReport.write(project, validation, dryRun = false)
    }

    private fun publishPrebuilt(publishInfo: PublishInfo, version: String) {
        val centralEnabled = project.extensions.findByType(PublishRepositories::class.java)
            ?.isCentralEnabled() == true
        val requireCentral = target == ExplicitPublishTarget.CENTRAL ||
            (target == ExplicitPublishTarget.ALL && centralEnabled)
        val path = project.findProperty("artifactBundlePath")?.toString()
            ?: System.getenv("PUBLISH_ARTIFACT_BUNDLE_PATH").orEmpty()
        val bundle = PrebuiltArtifactBundleProducer.prepare(project, path, version, requireCentral)
        if (requireCentral) validateCentralBundleNamespace(bundle, publishInfo)
        when (target) {
            ExplicitPublishTarget.LOCAL -> ArtifactBundlePublisher.publishToMavenLocal(project, bundle)
            ExplicitPublishTarget.GITHUB_PACKAGES -> publishPrebuiltGithub(bundle, publishInfo)
            ExplicitPublishTarget.CENTRAL -> publishPrebuiltCentral(bundle, publishInfo)
            ExplicitPublishTarget.ALL -> {
                val repositories = project.extensions.getByType(PublishRepositories::class.java)
                var githubSucceeded = false
                if (repositories.isGithubPackagesEnabled()) {
                    try {
                        publishPrebuiltGithub(bundle, publishInfo)
                        githubSucceeded = true
                    } catch (e: Exception) {
                        val suffix = if (repositories.isCentralEnabled()) "; Central was not started" else ""
                        throw GradleException("GitHub Packages prebuilt publishing failed$suffix: ${e.message}", e)
                    }
                }
                if (repositories.isCentralEnabled()) {
                    try {
                        publishPrebuiltCentral(bundle, publishInfo)
                    } catch (e: Exception) {
                        val prefix = if (githubSucceeded) "GitHub Packages succeeded; " else ""
                        throw GradleException("${prefix}Central prebuilt publishing failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun validateCentralBundleNamespace(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
        bundle.publications.forEach { publication ->
            if (publication.groupId != namespace && !publication.groupId.startsWith("$namespace.")) {
                throw GradleException(
                    "Prebuilt publication ${publication.groupId}:${publication.artifactId} must be under centralNamespace($namespace)"
                )
            }
        }
    }

    private fun publishPrebuiltGithub(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val config = PublishRuntimeConfig(project)
        val repository = PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, config.properties)
        val credentials = PublishConfigResolver.resolveGitHubPackagesCredentials(project, publishInfo, config.properties)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            throw GradleException("GitHub Packages prebuilt publishing requires GitHub Packages credentials")
        }
        ArtifactBundlePublisher.publishToRemote(project, bundle, repository, credentials.username, credentials.password)
    }

    private fun publishPrebuiltCentral(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val config = PublishRuntimeConfig(project)
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo, config.properties)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            throw GradleException("Central prebuilt publishing requires Central credentials")
        }
        if (PublishConfigResolver.resolveCentralUploadMode(project, publishInfo) == "portalApi") {
            val zip = CentralPortalBundle.create(bundle, File(project.buildDir, "reports/publish/central-bundle.zip"))
            val deploymentId = CentralPortalClient.uploadBundle(project, zip, publishInfo)
            CentralPortalClient.waitForDeployment(project, publishInfo, deploymentId)
            if (PublishConfigResolver.resolveCentralPublishingType(project, publishInfo) == "automatic") {
                CentralPortalClient.publishDeployment(project, publishInfo, deploymentId)
            }
        } else {
            ArtifactBundlePublisher.publishToRemote(
                project,
                bundle,
                PublishConfigResolver.resolveCentralRepositoryUrl(project, publishInfo),
                credentials.username,
                credentials.password
            )
            finalizeCentralPublication(publishInfo)
        }
    }

    private fun publishProject(publishInfo: PublishInfo) {
        fun targetProperties(targetName: String? = null): Map<String, String> =
            project.gradle.startParameter.projectProperties
                .filterValues { it.isNotBlank() }
                .toMutableMap()
                .apply { if (!targetName.isNullOrBlank()) this["publishTarget"] = targetName }
        when (target) {
            ExplicitPublishTarget.LOCAL -> runNested("publishToMavenLocal", targetProperties())
            ExplicitPublishTarget.GITHUB_PACKAGES -> runNested(
                remoteTaskName(PublishConfigResolver.MODE_GITHUB_PACKAGES),
                targetProperties("github_packages")
            )
            ExplicitPublishTarget.CENTRAL -> runNested(
                remoteTaskName(PublishConfigResolver.MODE_CENTRAL),
                targetProperties("central")
            ).also { finalizeCentralPublication(publishInfo) }
            ExplicitPublishTarget.ALL -> {
                val repositories = project.extensions.getByType(PublishRepositories::class.java)
                var githubSucceeded = false
                if (repositories.isGithubPackagesEnabled()) {
                    try {
                        runNested(remoteTaskName(PublishConfigResolver.MODE_GITHUB_PACKAGES), targetProperties("github_packages"))
                        githubSucceeded = true
                    } catch (e: Exception) {
                        val suffix = if (repositories.isCentralEnabled()) "; Central was not started" else ""
                        throw GradleException("GitHub Packages publishing failed$suffix: ${e.message}", e)
                    }
                }
                if (repositories.isCentralEnabled()) {
                    try {
                        runNested(remoteTaskName(PublishConfigResolver.MODE_CENTRAL), targetProperties("central"))
                        finalizeCentralPublication(publishInfo)
                    } catch (e: Exception) {
                        val prefix = if (githubSucceeded) "GitHub Packages succeeded; " else ""
                        throw GradleException("${prefix}Central publishing failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    private fun finalizeCentralPublication(publishInfo: PublishInfo) {
        if (!PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)) {
            CentralPortalClient.manualUpload(project, publishInfo)
        }
    }

    private fun remoteTaskName(mode: String): String {
        val repositoryName = if (mode == PublishConfigResolver.MODE_CENTRAL) {
            PublishConfigResolver.resolveCentralRepositoryName(project, project.extensions.getByType(PublishInfo::class.java))
        } else {
            PublishConfigResolver.resolveGitHubPackagesRepositoryName(project, project.extensions.getByType(PublishInfo::class.java))
        }
        val publishing = project.extensions.getByType(org.gradle.api.publish.PublishingExtension::class.java)
        val multiple = publishing.publications.names.count { it.endsWith(BasePublishTask.MAVEN_PUBLICATION_NAME) } > 1
        return if (multiple) {
            "publishAllPublicationsTo${repositoryName}Repository"
        } else {
            "publish${BasePublishTask.MAVEN_PUBLICATION_NAME}PublicationTo${repositoryName}Repository"
        }
    }

    private fun runNested(taskName: String, properties: Map<String, String>) {
        val output = ByteArrayOutputStream()
        val executable = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "gradlew.bat" else "gradlew"
        val path = File(project.rootProject.projectDir, executable)
        if (!path.exists()) throw GradleException("Gradle wrapper not found: ${path.path}")
        val realTask = project.path + ":" + taskName
        val result = project.exec { exec: ExecSpec ->
            exec.standardOutput = output
            exec.errorOutput = output
            exec.isIgnoreExitValue = true
            properties.forEach { (key, value) -> exec.environment("ORG_GRADLE_PROJECT_$key", value) }
            exec.commandLine(path.absolutePath)
            System.getProperty("maven.repo.local")?.takeIf { it.isNotBlank() }?.let {
                exec.args("-Dmaven.repo.local=$it")
            }
            exec.args(realTask, "--no-daemon", "--stacktrace")
        }
        if (result.exitValue != 0) {
            throw GradleException("Publish task failed ($realTask): ${output.toString().takeLast(4000)}")
        }
    }
}

open class PublishLocalTask : ExplicitPublishTask()
open class PublishRemoteGithubPackagesTask : ExplicitPublishTask()
open class PublishRemoteCentralTask : ExplicitPublishTask()
open class PublishRemoteAllTask : ExplicitPublishTask()
