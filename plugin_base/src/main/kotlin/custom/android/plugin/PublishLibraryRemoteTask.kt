package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.process.ExecSpec


open class PublishLibraryRemoteTask : BasePublishTask() {

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
        if (publishInfo.groupId.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("PublishInfo.groupId is required")
            return false
        }
        if (publishInfo.artifactId.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("PublishInfo.artifactId is required")
            return false
        }
        val resolvedVersion = PublishConfigResolver.resolveVersion(project, publishInfo)
        if (resolvedVersion.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("PublishInfo.version is required")
            return false
        }
        if (resolvedVersion.contains("debug", ignoreCase = true)) {
            PluginLogUtil.printlnErrorInScreen("$resolvedVersion contains debug")
            return false
        }

        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            return checkCustomRepositoryPublishInfo(publishInfo)
        }
        if (mode == PublishConfigResolver.MODE_GITHUB_PACKAGES) {
            return checkGitHubPackagesPublishInfo(publishInfo)
        }
        if (mode != PublishConfigResolver.MODE_CENTRAL) {
            PluginLogUtil.printlnErrorInScreen("Unsupported remotePublishMode: $mode")
            return false
        }
        return checkCentralPublishInfo(publishInfo)
    }

    private fun checkCustomRepositoryPublishInfo(publishInfo: PublishInfo): Boolean {
        val properties = PublishConfigResolver.loadLocalProperties(project)
        val publishUrl = PublishConfigResolver.resolveCustomRepositoryUrl(project, publishInfo, properties)
        if (publishUrl.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("customRepository mode requires publishUrl")
            return false
        }
        return true
    }

    private fun checkGitHubPackagesPublishInfo(publishInfo: PublishInfo): Boolean {
        val properties = PublishConfigResolver.loadLocalProperties(project)
        val publishUrl = PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, properties)
        if (publishUrl.isBlank()) {
            PluginLogUtil.printlnErrorInScreen(
                "githubPackages mode requires githubPackagesRepository or githubPackagesUrl"
            )
            return false
        }
        val credentials = PublishConfigResolver.resolveGitHubPackagesCredentials(project, publishInfo, properties)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            PluginLogUtil.printlnErrorInScreen(
                "githubPackages mode requires githubPackagesUsername/githubPackagesPassword or GITHUB_ACTOR/GITHUB_TOKEN"
            )
            return false
        }
        return true
    }

    private fun checkCentralPublishInfo(publishInfo: PublishInfo): Boolean {
        val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
        if (namespace.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("Central publish requires centralNamespace")
            return false
        }
        if (publishInfo.groupId != namespace && !publishInfo.groupId.startsWith("$namespace.")) {
            PluginLogUtil.printlnErrorInScreen(
                "PublishInfo.groupId(${publishInfo.groupId}) must be under centralNamespace($namespace)"
            )
            return false
        }
        val publishingType = PublishConfigResolver.resolveCentralPublishingType(project, publishInfo)
        if (publishingType != "user_managed" && publishingType != "automatic") {
            PluginLogUtil.printlnErrorInScreen("centralPublishingType only supports user_managed or automatic")
            return false
        }

        val requiredPomFields = mapOf(
            "pomDescription" to PublishConfigResolver.resolvePomDescription(project, publishInfo),
            "pomUrl" to PublishConfigResolver.resolvePomUrl(project, publishInfo),
            "developerId" to PublishConfigResolver.resolvePublishInfoText(
                project, "developerId", publishInfo, publishInfo.developerId
            ),
            "developerName" to PublishConfigResolver.resolvePublishInfoText(
                project, "developerName", publishInfo, publishInfo.developerName
            ),
            "developerEmail" to PublishConfigResolver.resolvePublishInfoText(
                project, "developerEmail", publishInfo, publishInfo.developerEmail
            ),
            "developerOrganization" to PublishConfigResolver.resolvePublishInfoText(
                project, "developerOrganization", publishInfo, publishInfo.developerOrganization
            ),
            "developerOrganizationUrl" to PublishConfigResolver.resolvePublishInfoText(
                project, "developerOrganizationUrl", publishInfo, publishInfo.developerOrganizationUrl
            ),
            "scmUrl" to PublishConfigResolver.resolveScmUrl(project, publishInfo),
            "scmConnection" to PublishConfigResolver.resolveScmConnection(project, publishInfo),
            "scmDeveloperConnection" to PublishConfigResolver.resolveScmDeveloperConnection(project, publishInfo)
        )
        val missingPomFields = requiredPomFields.filterValues { it.isBlank() }.keys
        if (missingPomFields.isNotEmpty()) {
            PluginLogUtil.printlnErrorInScreen("Central publish missing POM fields: ${missingPomFields.joinToString()}")
            return false
        }

        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            PluginLogUtil.printlnErrorInScreen("Central publish requires centralUsername/centralPassword")
            return false
        }

        val signingCredentials = PublishConfigResolver.resolveSigningCredentials(project)
        if (signingCredentials.key.isBlank() || signingCredentials.password.isBlank()) {
            PluginLogUtil.printlnErrorInScreen(
                "Central publish requires signingInMemoryKey and signingInMemoryKeyPassword"
            )
            return false
        }
        return true
    }

    override fun getPublishingExtensionRepositoriesPath(publishing: PublishingExtension): String {
        val publishInfo = project.extensions.getByType(PublishInfo::class.java)
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CENTRAL) {
            return PublishConfigResolver.CENTRAL_STAGING_URL
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

    override fun printRemoteArtifactVerificationPath(): Boolean = true

    override fun afterPublishSuccess(publishInfo: PublishInfo, output: String) {
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CENTRAL) {
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
