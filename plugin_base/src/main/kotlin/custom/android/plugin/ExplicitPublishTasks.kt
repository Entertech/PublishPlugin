package custom.android.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
    var target: ExplicitPublishTarget = ExplicitPublishTarget.LOCAL
    var componentKind: PublishComponentKind = PublishComponentKind.LIBRARY

    init {
        group = "customPlugin"
    }

    @TaskAction
    fun publish() {
        validateTargetConfiguration()
        val source = ArtifactSource.parse(
            project.findProperty("artifactSource")?.toString()
                ?: System.getenv("PUBLISH_ARTIFACT_SOURCE")
        )
        val publishInfo = project.extensions.findByType(PublishInfo::class.java)
            ?: throw GradleException("PublishInfo is required for ${componentKind.taskNamePart} publishing")
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        if (source == ArtifactSource.PREBUILT) {
            publishPrebuilt(publishInfo, version)
        } else {
            publishProject(publishInfo)
        }
    }

    private fun validateTargetConfiguration() {
        if (target == ExplicitPublishTarget.LOCAL) return
        val repositories = project.extensions.findByType(PublishRepositories::class.java)
            ?: throw GradleException("PublishRepositories configuration is required for remote publishing")
        val enabled = repositories.enabledRemoteProviderIds()
        when (target) {
            ExplicitPublishTarget.GITHUB_PACKAGES -> if (PublishConfigResolver.MODE_GITHUB_PACKAGES !in enabled) {
                throw GradleException("GitHub Packages is not enabled. Configure PublishRepositories.githubPackages { enabled = true }")
            }
            ExplicitPublishTarget.CENTRAL -> if (PublishConfigResolver.MODE_CENTRAL !in enabled) {
                throw GradleException("Central is not enabled. Configure PublishRepositories.central { enabled = true }")
            }
            ExplicitPublishTarget.ALL -> if (enabled.isEmpty()) {
                throw GradleException("RemoteAllTask requires at least one enabled remote repository provider")
            }
            ExplicitPublishTarget.LOCAL -> Unit
        }
    }

    private fun publishPrebuilt(publishInfo: PublishInfo, version: String) {
        val centralEnabled = project.extensions.findByType(PublishRepositories::class.java)
            ?.isCentralEnabled() == true
        val requireCentral = target == ExplicitPublishTarget.CENTRAL ||
            (target == ExplicitPublishTarget.ALL && centralEnabled)
        val path = project.findProperty("artifactBundlePath")?.toString()
            ?: System.getenv("PUBLISH_ARTIFACT_BUNDLE_PATH").orEmpty()
        val bundle = PrebuiltArtifactBundleProducer.prepare(project, path, version, requireCentral)
        when (target) {
            ExplicitPublishTarget.LOCAL -> ArtifactBundlePublisher.publishToMavenLocal(project, bundle)
            ExplicitPublishTarget.GITHUB_PACKAGES -> publishPrebuiltGithub(bundle, publishInfo)
            ExplicitPublishTarget.CENTRAL -> publishPrebuiltCentral(bundle, publishInfo)
            ExplicitPublishTarget.ALL -> {
                val repositories = project.extensions.getByType(PublishRepositories::class.java)
                if (repositories.isGithubPackagesEnabled()) publishPrebuiltGithub(bundle, publishInfo)
                if (repositories.isCentralEnabled()) publishPrebuiltCentral(bundle, publishInfo)
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
        ArtifactBundlePublisher.publishToRemote(
            project,
            bundle,
            PublishConfigResolver.resolveCentralRepositoryUrl(project),
            credentials.username,
            credentials.password
        )
    }

    private fun publishProject(@Suppress("UNUSED_PARAMETER") publishInfo: PublishInfo) {
        fun targetProperties(targetName: String): Map<String, String> =
            project.gradle.startParameter.projectProperties
                .filterValues { it.isNotBlank() }
                .toMutableMap()
                .apply { this["publishTarget"] = targetName }
        when (target) {
            ExplicitPublishTarget.LOCAL -> runNested("publishToMavenLocal", emptyMap())
            ExplicitPublishTarget.GITHUB_PACKAGES -> runNested(
                remoteTaskName(PublishConfigResolver.MODE_GITHUB_PACKAGES),
                targetProperties("github_packages")
            )
            ExplicitPublishTarget.CENTRAL -> runNested(
                remoteTaskName(PublishConfigResolver.MODE_CENTRAL),
                targetProperties("central")
            )
            ExplicitPublishTarget.ALL -> {
                val repositories = project.extensions.getByType(PublishRepositories::class.java)
                if (repositories.isGithubPackagesEnabled()) {
                    runNested(
                        remoteTaskName(PublishConfigResolver.MODE_GITHUB_PACKAGES),
                        targetProperties("github_packages")
                    )
                }
                if (repositories.isCentralEnabled()) {
                    runNested(
                        remoteTaskName(PublishConfigResolver.MODE_CENTRAL),
                        targetProperties("central")
                    )
                }
            }
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
            exec.commandLine(path.absolutePath, realTask, "--no-daemon", "--stacktrace")
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
