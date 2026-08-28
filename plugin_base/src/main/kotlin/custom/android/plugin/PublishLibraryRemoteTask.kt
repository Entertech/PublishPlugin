package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.process.ExecSpec


/**
 * Legacy implementation retained only so older compiled callers can be migrated
 * without reintroducing the task. It is intentionally not registered by
 * [PublishPlugin].
 */
@Deprecated("Removed from the public task API; use PublishLibraryRemoteGithubPackagesTask or PublishLibraryRemoteCentralTask")
internal open class PublishLibraryRemoteTask : BasePublishTask() {

    companion object {
        const val TAG = "PublishLibraryRemoteTask"
    }


    override fun initPublishCommandLine(): String {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        val repositoryName = when (mode) {
            PublishConfigResolver.MODE_CUSTOM_REPOSITORY -> "Maven"
            PublishConfigResolver.MODE_GITHUB_PACKAGES ->
                PublishConfigResolver.resolveGitHubPackagesRepositoryName(project, publishInfo)
            else -> PublishConfigResolver.resolveCentralRepositoryName(project, publishInfo)
        }
        if (hasMultipleEnterPublications()) {
            return ":publishAllPublicationsTo${repositoryName}Repository"
        }
        if (mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            return ":publish${MAVEN_PUBLICATION_NAME}PublicationToMavenRepository"
        }
        return ":publish${MAVEN_PUBLICATION_NAME}PublicationTo${repositoryName}Repository"
    }


    override fun checkPublishInfo(publishInfo: PublishInfo): Boolean {
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            val publishUrl = PublishConfigResolver.resolveCustomRepositoryUrl(project, publishInfo)
            if (publishUrl.isBlank()) {
                PluginLogUtil.printlnErrorInScreen("customRepository mode requires publishUrl")
                return false
            }
            return true
        }
        val target = when (mode) {
            PublishConfigResolver.MODE_GITHUB_PACKAGES -> ExplicitPublishTarget.GITHUB_PACKAGES
            PublishConfigResolver.MODE_CENTRAL,
            PublishConfigResolver.MODE_CENTRAL_SNAPSHOT -> ExplicitPublishTarget.CENTRAL
            else -> {
                PluginLogUtil.printlnErrorInScreen("Unsupported publishTarget: $mode")
                return false
            }
        }
        val result = PublishValidation.validateRemote(project, publishInfo, target, ArtifactSource.PROJECT)
        result.warnings.forEach { PluginLogUtil.printlnInfoInScreen("WARNING: $it") }
        result.errors.forEach { PluginLogUtil.printlnErrorInScreen(it) }
        return result.valid
    }

    override fun getPublishingExtensionRepositoriesPath(publishing: PublishingExtension): String {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CENTRAL || mode == PublishConfigResolver.MODE_CENTRAL_SNAPSHOT) {
            return PublishConfigResolver.resolveCentralRepositoryUrl(project, publishInfo)
        }
        val repositoryName = if (mode == PublishConfigResolver.MODE_GITHUB_PACKAGES) {
            PublishConfigResolver.resolveGitHubPackagesRepositoryName(project, publishInfo)
        } else {
            "Maven"
        }
        return (publishing.repositories.findByName(repositoryName) as? MavenArtifactRepository)
            ?.url
            ?.toString()
            .orEmpty()
    }

    override fun printRemoteArtifactVerificationPath(): Boolean {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        return PublishConfigResolver.resolveRemotePublishMode(project, publishInfo) ==
            PublishConfigResolver.MODE_GITHUB_PACKAGES
    }

    override fun repositoryWebPageUrl(repositoryPath: String): String {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode != PublishConfigResolver.MODE_GITHUB_PACKAGES) {
            return ""
        }
        return githubPackagesWebPageUrl(repositoryPath)
    }

    private fun githubPackagesWebPageUrl(repositoryPath: String): String {
        val prefix = "https://maven.pkg.github.com/"
        val path = repositoryPath.trimEnd('/').removePrefix(prefix)
        if (path == repositoryPath.trimEnd('/')) {
            return ""
        }
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) {
            return ""
        }
        return "https://github.com/${parts[0]}/${parts[1]}/packages"
    }

    override fun afterPublishSuccess(publishInfo: PublishInfo, output: String) {
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if ((mode == PublishConfigResolver.MODE_CENTRAL || mode == PublishConfigResolver.MODE_CENTRAL_SNAPSHOT) &&
            !PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)
        ) {
            try {
                CentralPortalClient.manualUpload(project, publishInfo)
            } catch (e: GradleException) {
                throw e
            } catch (e: Exception) {
                throw GradleException("Central Portal manual upload failed: ${e.message}", e)
            }
        }
    }

    override fun configureNestedGradleExec(exec: ExecSpec, publishInfo: PublishInfo) {
        forwardedProjectProperties.forEach { propertyName ->
            val value = forwardedProjectPropertyValue(propertyName)
            if (!value.isNullOrBlank()) {
                exec.environment("ORG_GRADLE_PROJECT_$propertyName", value)
                forwardedProjectPropertyAliases[propertyName]?.let { alias ->
                    exec.environment("ORG_GRADLE_PROJECT_$alias", value)
                }
            }
        }
    }

    private fun forwardedProjectPropertyValue(propertyName: String): String? {
        if (propertyName == "version") {
            return project.gradle.startParameter.projectProperties[propertyName]
        }
        return project.findProperty(propertyName)?.toString()
    }

    override fun fetchTaskName(): String = TAG

    private fun hasMultipleEnterPublications(): Boolean {
        val publishing = project.extensions.findByType(PublishingExtension::class.java) ?: return false
        return publishing.publications.names.count { it.endsWith(MAVEN_PUBLICATION_NAME) } > 1
    }

    private val forwardedProjectProperties = listOf(
        "remotePublishMode",
        "publishTarget",
        "publishUrl",
        "publishUserName",
        "publishPassword",
        "githubPackagesRepository",
        "githubPackagesUrl",
        "githubPackagesRepositoryName",
        "githubPackagesUsername",
        "githubPackagesPassword",
        "gpr.user",
        "gpr.key",
        "publishVersion",
        "version",
        "centralNamespace",
        "centralPublishingType",
        "centralRepositoryName",
        "centralReleaseType",
        "centralUploadMode",
        "centralPortalApiBaseUrl",
        "centralUsername",
        "centralPassword",
        "mavenCentralUsername",
        "mavenCentralPassword",
        "pomName",
        "pomDescription",
        "pomInceptionYear",
        "pomUrl",
        "licenseName",
        "licenseUrl",
        "licenseDistribution",
        "developerId",
        "developerName",
        "developerEmail",
        "developerOrganization",
        "developerOrganizationUrl",
        "developerUrl",
        "scmUrl",
        "scmConnection",
        "scmDeveloperConnection",
        "signingInMemoryKey",
        "signingInMemoryKeyId",
        "signingInMemoryKeyPassword",
        "signingKeyId",
        "signingPassword"
    )

    private val forwardedProjectPropertyAliases = mapOf(
        "gpr.user" to "githubPackagesUsername",
        "gpr.key" to "githubPackagesPassword"
    )
}
