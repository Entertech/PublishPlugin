package custom.android.plugin

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

data class PublishValidationPublication(
    val name: String,
    val groupId: String,
    val artifactId: String,
    val version: String
)

data class PublishValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val mode: String,
    val repositoryName: String,
    val repositoryUrl: String,
    val publications: List<PublishValidationPublication>,
    val validationLevel: PublishValidationLevel = PublishValidationLevel.REMOTE,
    val credentialSources: Map<String, String> = emptyMap(),
    val preflightResults: List<PublishPreflightResult> = emptyList(),
    val providerResults: List<PublishProviderResult> = emptyList(),
    val gates: List<PublishGateResult> = emptyList(),
    val provenance: Map<String, String> = emptyMap()
)

object PublishValidation {
    fun validateRemote(
        project: Project,
        publishInfo: PublishInfo,
        target: ExplicitPublishTarget,
        source: ArtifactSource = ArtifactSource.PROJECT,
        level: PublishValidationLevel = PublishValidationLevel.REMOTE
    ): PublishValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val publications = publications(project)
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        val repositories = project.extensions.findByType(PublishRepositories::class.java)
        val credentialSources = PublishCredentialSources.summarize(project, target)
        val mode = when (target) {
            ExplicitPublishTarget.CENTRAL -> {
                if (PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)) {
                    PublishConfigResolver.MODE_CENTRAL_SNAPSHOT
                } else {
                    target.modeName()
                }
            }
            else -> target.modeName()
        }

        if (version.isBlank()) errors += "PublishInfo.version or publishVersion is required for remote publishing"
        if (target != ExplicitPublishTarget.LOCAL && version.contains("debug", ignoreCase = true)) {
            errors += "Remote publishing refuses debug version: $version"
        }
        if (source == ArtifactSource.PROJECT) {
            if (publishInfo.groupId.isBlank()) errors += "PublishInfo.groupId is required"
            if (publishInfo.artifactId.isBlank()) errors += "PublishInfo.artifactId is required"
        }
        if (publications.isEmpty()) warnings += "No *EnterPublish Maven publication is available yet"

        fun validateGithub() {
            val config = PublishRuntimeConfig(project)
            val url = PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, config.properties)
            if (url.isBlank()) errors += "GitHub Packages requires githubPackagesRepository or githubPackagesUrl"
            if (level.includesCredentials && (
                    credentialSources["githubPackages.username"] == "missing" ||
                        credentialSources["githubPackages.token"] == "missing"
                    )) {
                errors += "GitHub Packages requires package credentials (values are never printed)"
            }
        }

        fun validateCentral() {
            val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
            if (namespace.isBlank()) errors += "Central requires centralNamespace"
            val publishingType = PublishConfigResolver.resolveCentralPublishingType(project, publishInfo)
            if (publishingType != "user_managed" && publishingType != "automatic") {
                errors += "centralPublishingType only supports user_managed or automatic"
            }
            val snapshot = PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)
            if (snapshot && !version.endsWith("-SNAPSHOT", ignoreCase = true)) {
                errors += "Central snapshot publishing requires a -SNAPSHOT version"
            }
            if (!snapshot && version.endsWith("-SNAPSHOT", ignoreCase = true)) {
                errors += "Release Central publishing cannot use a -SNAPSHOT version"
            }
            if (source == ArtifactSource.PROJECT) {
                val projectPublications = publications.ifEmpty {
                    listOf(PublishValidationPublication("PublishInfo", publishInfo.groupId, publishInfo.artifactId, version))
                }
                val outsideNamespace = projectPublications.filter { publication ->
                    publication.groupId != namespace && !publication.groupId.startsWith("$namespace.")
                }
                if (outsideNamespace.isNotEmpty()) {
                    errors += "Central publication groups must be under centralNamespace($namespace): " +
                        outsideNamespace.joinToString { "${it.name}=${it.groupId}" }
                }
                val requiredPomFields = mapOf(
                    "pomDescription" to PublishConfigResolver.resolvePomDescription(project, publishInfo),
                    "pomUrl" to PublishConfigResolver.resolvePomUrl(project, publishInfo),
                    "developerId" to PublishConfigResolver.resolvePublishInfoText(project, "developerId", publishInfo, publishInfo.developerId),
                    "developerName" to PublishConfigResolver.resolvePublishInfoText(project, "developerName", publishInfo, publishInfo.developerName),
                    "developerEmail" to PublishConfigResolver.resolvePublishInfoText(project, "developerEmail", publishInfo, publishInfo.developerEmail),
                    "developerOrganization" to PublishConfigResolver.resolvePublishInfoText(project, "developerOrganization", publishInfo, publishInfo.developerOrganization),
                    "developerOrganizationUrl" to PublishConfigResolver.resolvePublishInfoText(project, "developerOrganizationUrl", publishInfo, publishInfo.developerOrganizationUrl),
                    "scmUrl" to PublishConfigResolver.resolveScmUrl(project, publishInfo),
                    "scmConnection" to PublishConfigResolver.resolveScmConnection(project, publishInfo),
                    "scmDeveloperConnection" to PublishConfigResolver.resolveScmDeveloperConnection(project, publishInfo)
                )
                val missing = requiredPomFields.filterValues { it.isBlank() }.keys
                if (missing.isNotEmpty()) errors += "Central publish missing POM fields: ${missing.joinToString()}"
                if (level.includesCredentials && (
                        credentialSources["signing.key"] == "missing" ||
                            credentialSources["signing.password"] == "missing"
                        )) {
                    errors += "Central project publishing requires signing credentials"
                }
            }
            if (level.includesCredentials && (
                    credentialSources["central.username"] == "missing" ||
                        credentialSources["central.password"] == "missing"
                    )) {
                errors += "Central publishing requires Central credentials"
            }
        }

        when (target) {
            ExplicitPublishTarget.LOCAL -> Unit
            ExplicitPublishTarget.GITHUB_PACKAGES -> {
                if (repositories?.isGithubPackagesEnabled() != true) {
                    errors += "GitHub Packages is not enabled. Configure PublishRepositories.githubPackages { enabled = true }"
                }
                validateGithub()
            }
            ExplicitPublishTarget.CENTRAL -> {
                if (repositories?.isCentralEnabled() != true) {
                    errors += "Central is not enabled. Configure PublishRepositories.central { enabled = true }"
                }
                validateCentral()
            }
            ExplicitPublishTarget.ALL -> {
                if (repositories?.isGithubPackagesEnabled() == true) validateGithub()
                if (repositories?.isCentralEnabled() == true) validateCentral()
                if (repositories == null || repositories.enabledRemoteProviderIds().isEmpty()) {
                    errors += "RemoteAllTask requires at least one enabled remote repository provider"
                }
            }
        }

        if (target != ExplicitPublishTarget.LOCAL && mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            val url = PublishConfigResolver.resolveCustomRepositoryUrl(project, publishInfo)
            if (url.isBlank()) errors += "customRepository mode requires publishUrl"
        }

        val repositoryName = when (target) {
            ExplicitPublishTarget.CENTRAL -> PublishConfigResolver.resolveCentralRepositoryName(project, publishInfo)
            ExplicitPublishTarget.GITHUB_PACKAGES -> PublishConfigResolver.resolveGitHubPackagesRepositoryName(project, publishInfo)
            ExplicitPublishTarget.ALL -> "Multiple"
            ExplicitPublishTarget.LOCAL -> "MavenLocal"
        }
        val repositoryUrl = when (target) {
            ExplicitPublishTarget.CENTRAL -> PublishConfigResolver.resolveCentralRepositoryUrl(project, publishInfo)
            ExplicitPublishTarget.GITHUB_PACKAGES -> PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo)
            ExplicitPublishTarget.ALL, ExplicitPublishTarget.LOCAL -> ""
        }
        return PublishValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            mode = mode,
            repositoryName = repositoryName,
            repositoryUrl = repositoryUrl,
            publications = publications,
            validationLevel = level,
            credentialSources = credentialSources
        )
    }

    fun publications(project: Project): List<PublishValidationPublication> {
        val publishing = project.extensions.findByType(PublishingExtension::class.java) ?: return emptyList()
        val all = publishing.publications.withType(MavenPublication::class.java).toList()
        val enterPublications = all.filter { it.name.endsWith(BasePublishTask.MAVEN_PUBLICATION_NAME) }
        val selected = if (PublishComponentKind.detect(project.plugins) == PublishComponentKind.PLUGIN) {
            enterPublications + all.filter { it.name.endsWith("PluginMarkerMaven") }
        } else {
            enterPublications
        }
        return selected.distinctBy { it.name }
            .ifEmpty { all }
            .map { PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version) }
    }

    private fun ExplicitPublishTarget.modeName(): String = when (this) {
        ExplicitPublishTarget.LOCAL -> "local"
        ExplicitPublishTarget.GITHUB_PACKAGES -> PublishConfigResolver.MODE_GITHUB_PACKAGES
        ExplicitPublishTarget.CENTRAL -> PublishConfigResolver.MODE_CENTRAL
        ExplicitPublishTarget.ALL -> "all"
    }
}
