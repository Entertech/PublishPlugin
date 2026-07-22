package custom.android.plugin

import custom.android.plugin.config.PublishConfig
import custom.android.plugin.config.PublishConfigLoader
import custom.android.plugin.config.GitUrlNormalizer
import custom.android.plugin.config.PomMetadataDefaults
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object PublishConfigResolver {
    const val CENTRAL_STAGING_URL =
        "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
    const val CENTRAL_MANUAL_UPLOAD_BASE_URL =
        "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository"
    const val MODE_CENTRAL = "central"
    const val MODE_CUSTOM_REPOSITORY = "customRepository"
    const val MODE_GITHUB_PACKAGES = "githubPackages"
    const val WORKFLOW_TARGET_CENTRAL = "central"
    const val WORKFLOW_TARGET_GITHUB_PACKAGES = "github_packages"
    const val WORKFLOW_TARGET_ALL = "all"
    const val DEFAULT_GITHUB_PACKAGES_REPOSITORY_NAME = "GitHubPackages"

    data class CentralCredentials(
        val username: String,
        val password: String
    )

    data class RepositoryCredentials(
        val username: String,
        val password: String
    )

    data class SigningCredentials(
        val keyId: String,
        val key: String,
        val password: String
    )

    fun loadLocalProperties(project: Project): Properties {
        val properties = Properties()
        val file = project.rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { properties.load(it) }
        }
        return properties
    }

    fun publishConfigFile(project: Project): File {
        val configuredPath = projectProperty(project, "publishConfig")
        if (configuredPath.isBlank()) {
            return project.rootProject.file("local.properties")
        }
        val configuredFile = File(configuredPath)
        return if (configuredFile.isAbsolute) configuredFile else project.rootProject.file(configuredPath)
    }

    fun loadPublishProperties(project: Project): PublishConfig {
        return PublishConfigLoader.load(publishConfigFile(project))
    }

    fun resolveRemotePublishMode(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "remotePublishMode"),
            environment("REMOTE_PUBLISH_MODE"),
            publishInfo.remotePublishMode
        ).ifBlank { MODE_GITHUB_PACKAGES }
    }

    fun resolveVersion(
        project: Project,
        publishInfo: PublishInfo,
        configuredVersion: String = publishInfo.version
    ): String {
        return firstNotBlank(
            commandLineProjectProperty(project, "publishVersion"),
            projectProperty(project, "publishVersion"),
            environment("PUBLISH_VERSION"),
            commandLineProjectProperty(project, "version"),
            projectProperty(project, "version").takeUnless { it == Project.DEFAULT_VERSION },
            configuredVersion
        )
    }

    fun resolveWorkflowPublishTarget(project: Project, config: PublishConfig): String {
        val normalized = normalizeWorkflowPublishTarget(
            firstNotBlank(
                projectProperty(project, "publishTarget"),
                environment("PUBLISH_TARGET"),
                config.publishTarget
            ).ifBlank { WORKFLOW_TARGET_GITHUB_PACKAGES }
        )
        if (
            normalized == WORKFLOW_TARGET_CENTRAL ||
            normalized == WORKFLOW_TARGET_GITHUB_PACKAGES ||
            normalized == WORKFLOW_TARGET_ALL
        ) {
            return normalized
        }
        throw IllegalArgumentException(
            "publishTarget only supports central, github_packages, or all, but was $normalized"
        )
    }

    fun requiresCentralForWorkflowTarget(publishTarget: String): Boolean {
        val normalized = normalizeWorkflowPublishTarget(publishTarget)
        return normalized == WORKFLOW_TARGET_CENTRAL || normalized == WORKFLOW_TARGET_ALL
    }

    fun normalizeWorkflowPublishTarget(value: String): String {
        return when (value.trim()) {
            MODE_GITHUB_PACKAGES -> WORKFLOW_TARGET_GITHUB_PACKAGES
            WORKFLOW_TARGET_GITHUB_PACKAGES,
            WORKFLOW_TARGET_CENTRAL,
            WORKFLOW_TARGET_ALL -> value.trim()
            else -> value.trim()
        }
    }

    fun isCentralPublish(project: Project, publishInfo: PublishInfo): Boolean {
        val mode = resolveRemotePublishMode(project, publishInfo)
        if (mode != MODE_CENTRAL) {
            return false
        }
        if (projectProperty(project, "centralPublish").toBooleanLenient()) {
            return true
        }
        val publishTaskPrefix = "publish${BasePublishTask.MAVEN_PUBLICATION_NAME}PublicationTo"
        return project.gradle.startParameter.taskNames.any { taskName ->
            val publishRepositoryTask = taskName.contains(publishTaskPrefix) && taskName.contains("Repository")
            taskName.contains(PublishLibraryRemoteTask.TAG) ||
                taskName.contains("CentralStagingRepository") ||
                publishRepositoryTask ||
                taskName.contains("sign${BasePublishTask.MAVEN_PUBLICATION_NAME}Publication")
        }
    }

    fun resolveCentralRepositoryName(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralRepositoryName"),
            environment("CENTRAL_REPOSITORY_NAME"),
            explicitPublishInfoValue(publishInfo, "centralRepositoryName", publishInfo.centralRepositoryName),
            loadPublishProperties(project).centralRepositoryName,
            "CentralStaging"
        )
    }

    fun resolveCentralNamespace(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralNamespace"),
            environment("CENTRAL_NAMESPACE"),
            explicitPublishInfoValue(publishInfo, "centralNamespace", publishInfo.centralNamespace),
            loadPublishProperties(project).centralNamespace,
            publishInfo.centralNamespace
        )
    }

    fun resolveCentralPublishingType(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralPublishingType"),
            environment("CENTRAL_PUBLISHING_TYPE"),
            explicitPublishInfoValue(publishInfo, "centralPublishingType", publishInfo.centralPublishingType),
            loadPublishProperties(project).centralPublishingType,
            "user_managed"
        )
    }

    fun resolveCentralCredentials(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): CentralCredentials {
        val username = firstNotBlank(
            projectProperty(project, "centralUsername"),
            environment("CENTRAL_USERNAME"),
            projectProperty(project, "mavenCentralUsername"),
            environment("MAVEN_CENTRAL_USERNAME"),
            publishInfo.publishUserName,
            localProperties.getProperty("publishUserName")
        )
        val password = firstNotBlank(
            projectProperty(project, "centralPassword"),
            environment("CENTRAL_PASSWORD"),
            projectProperty(project, "mavenCentralPassword"),
            environment("MAVEN_CENTRAL_PASSWORD"),
            publishInfo.publishPassword,
            localProperties.getProperty("publishPassword")
        )
        return CentralCredentials(username, password)
    }

    fun resolveCustomRepositoryUrl(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): String {
        return firstNotBlank(
            projectProperty(project, "publishUrl"),
            publishInfo.publishUrl,
            localProperties.getProperty("publishUrl")
        )
    }

    fun resolveCustomRepositoryUsername(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): String {
        return firstNotBlank(
            projectProperty(project, "publishUserName"),
            publishInfo.publishUserName,
            localProperties.getProperty("publishUserName")
        )
    }

    fun resolveCustomRepositoryPassword(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): String {
        return firstNotBlank(
            projectProperty(project, "publishPassword"),
            publishInfo.publishPassword,
            localProperties.getProperty("publishPassword")
        )
    }

    fun resolveGitHubPackagesRepositoryName(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "githubPackagesRepositoryName"),
            environment("GITHUB_PACKAGES_REPOSITORY_NAME"),
            publishInfo.githubPackagesRepositoryName,
            DEFAULT_GITHUB_PACKAGES_REPOSITORY_NAME
        )
    }

    fun resolveGitHubPackagesUrl(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): String {
        val publishProperties = loadPublishProperties(project)
        return firstNotBlank(
            projectProperty(project, "githubPackagesUrl"),
            environment("GITHUB_PACKAGES_URL"),
            publishInfo.githubPackagesUrl,
            publishProperties.githubPackagesUrl,
            localProperties.getProperty("githubPackagesUrl"),
            githubPackagesUrlFromRepository(resolveGitHubPackagesRepository(project, publishInfo, localProperties))
        )
    }

    fun resolveGitHubPackagesCredentials(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties = loadLocalProperties(project)
    ): RepositoryCredentials {
        val username = firstNotBlank(
            projectProperty(project, "githubPackagesUsername"),
            projectProperty(project, "gpr.user"),
            environment("GITHUB_PACKAGES_USER"),
            environment("GITHUB_ACTOR"),
            environment("USERNAME"),
            publishInfo.githubPackagesUsername,
            publishInfo.publishUserName,
            localProperties.getProperty("githubPackagesUsername"),
            localProperties.getProperty("publishUserName")
        )
        val password = firstNotBlank(
            projectProperty(project, "githubPackagesPassword"),
            projectProperty(project, "gpr.key"),
            environment("GITHUB_PACKAGES_TOKEN"),
            environment("GITHUB_TOKEN"),
            environment("TOKEN"),
            publishInfo.githubPackagesPassword,
            publishInfo.publishPassword,
            localProperties.getProperty("githubPackagesPassword"),
            localProperties.getProperty("publishPassword")
        )
        return RepositoryCredentials(username, password)
    }

    fun resolveSigningCredentials(project: Project): SigningCredentials {
        return SigningCredentials(
            keyId = firstNotBlank(
                projectProperty(project, "signingInMemoryKeyId"),
                environment("SIGNING_IN_MEMORY_KEY_ID"),
                projectProperty(project, "signingKeyId"),
                environment("SIGNING_KEY_ID")
            ),
            key = firstNotBlank(
                projectProperty(project, "signingInMemoryKey"),
                environment("SIGNING_IN_MEMORY_KEY"),
                environment("GPG_KEY_CONTENTS")
            ),
            password = firstNotBlank(
                projectProperty(project, "signingInMemoryKeyPassword"),
                environment("SIGNING_IN_MEMORY_KEY_PASSWORD"),
                projectProperty(project, "signingPassword"),
                environment("SIGNING_PASSWORD")
            )
        )
    }

    fun resolveScmUrl(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmUrl"),
            environment("SCM_URL"),
            explicitPublishInfoValue(publishInfo, "scmUrl", publishInfo.scmUrl),
            loadPublishProperties(project).scmUrl,
            inferScmUrl(project)
        )
    }

    fun resolveScmConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmConnection"),
            environment("SCM_CONNECTION"),
            explicitPublishInfoValue(publishInfo, "scmConnection", publishInfo.scmConnection),
            loadPublishProperties(project).scmConnection,
            scmConnectionFromUrl(resolveScmUrl(project, publishInfo))
        )
    }

    fun resolveScmDeveloperConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmDeveloperConnection"),
            environment("SCM_DEVELOPER_CONNECTION"),
            explicitPublishInfoValue(publishInfo, "scmDeveloperConnection", publishInfo.scmDeveloperConnection),
            loadPublishProperties(project).scmDeveloperConnection,
            scmDeveloperConnectionFromUrl(resolveScmUrl(project, publishInfo))
        )
    }

    fun resolvePomName(project: Project, publishInfo: PublishInfo, artifactId: String): String {
        return firstNotBlank(
            projectProperty(project, "pomName"),
            environment("POM_NAME"),
            explicitPublishInfoValue(publishInfo, "pomName", publishInfo.pomName),
            PomMetadataDefaults.defaultPomName(project, artifactId)
        )
    }

    fun resolvePomDescription(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "pomDescription"),
            environment("POM_DESCRIPTION"),
            explicitPublishInfoValue(publishInfo, "pomDescription", publishInfo.pomDescription),
            PomMetadataDefaults.defaultPomDescription(project)
        )
    }

    fun resolvePomUrl(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "pomUrl"),
            environment("POM_URL"),
            explicitPublishInfoValue(publishInfo, "pomUrl", publishInfo.pomUrl),
            inferScmUrl(project),
            resolveScmUrl(project, publishInfo)
        )
    }

    fun resolveText(project: Project, propertyName: String, publishInfoValue: String, defaultValue: String = ""): String {
        return firstNotBlank(
            projectProperty(project, propertyName),
            environment(propertyName.camelToUpperSnake()),
            publishInfoValue,
            defaultValue
        )
    }

    fun resolvePublishInfoText(
        project: Project,
        propertyName: String,
        publishInfo: PublishInfo,
        publishInfoValue: String,
        defaultValue: String = publishInfoValue
    ): String {
        return firstNotBlank(
            projectProperty(project, propertyName),
            environment(propertyName.camelToUpperSnake()),
            explicitPublishInfoValue(publishInfo, propertyName, publishInfoValue),
            loadPublishProperties(project).valueFor(propertyName),
            defaultValue
        )
    }

    private fun inferScmUrl(project: Project): String {
        val githubRepository = environment("GITHUB_REPOSITORY")
        val githubUrl = if (githubRepository.isNotBlank()) {
            val githubServerUrl = environment("GITHUB_SERVER_URL").ifBlank { "https://github.com" }
            "${githubServerUrl.trimEnd('/')}/$githubRepository"
        } else {
            ""
        }

        return firstNotBlank(
            GitUrlNormalizer.toHttpsRepositoryUrl(readPreferredGitRemoteUrl(project)),
            githubUrl,
            environment("CI_PROJECT_URL"),
            GitUrlNormalizer.toHttpsRepositoryUrl(environment("GIT_URL")),
            GitUrlNormalizer.toHttpsRepositoryUrl(environment("BUILD_REPOSITORY_URI"))
        )
    }

    private fun resolveGitHubPackagesRepository(
        project: Project,
        publishInfo: PublishInfo,
        localProperties: Properties
    ): String {
        val publishProperties = loadPublishProperties(project)
        return normalizeOwnerRepo(
            firstNotBlank(
                projectProperty(project, "githubPackagesRepository"),
                environment("GITHUB_PACKAGES_REPOSITORY"),
                publishInfo.githubPackagesRepository,
                publishProperties.githubPackagesRepository,
                localProperties.getProperty("githubPackagesRepository"),
                environment("GITHUB_REPOSITORY"),
                repositoryFromGitOrigin(project)
            )
        )
    }

    private fun githubPackagesUrlFromRepository(repository: String): String {
        return if (repository.isBlank()) {
            ""
        } else {
            "https://maven.pkg.github.com/$repository"
        }
    }

    private fun repositoryFromGitOrigin(project: Project): String {
        return normalizeOwnerRepo(GitUrlNormalizer.toHttpsRepositoryUrl(readPreferredGitRemoteUrl(project)))
    }

    private fun normalizeOwnerRepo(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return ""
        }
        val withoutGit = trimmed.removeSuffix(".git")
        return when {
            withoutGit.startsWith("https://github.com/") -> withoutGit.removePrefix("https://github.com/")
            withoutGit.startsWith("http://github.com/") -> withoutGit.removePrefix("http://github.com/")
            withoutGit.startsWith("git@github.com:") -> withoutGit.removePrefix("git@github.com:")
            withoutGit.startsWith("ssh://git@github.com/") -> withoutGit.removePrefix("ssh://git@github.com/")
            else -> withoutGit
        }
    }

    private fun readPreferredGitRemoteUrl(project: Project): String {
        val remotes = findGitConfigs(project.rootProject.projectDir)
            .flatMap { readGitRemotes(it) }
        val githubRemotes = remotes
            .mapNotNull { remote ->
                val repositoryUrl = GitUrlNormalizer.toHttpsRepositoryUrl(remote.url)
                if (repositoryUrl.startsWith("https://github.com/")) {
                    GitRemote(remote.name, remote.url, repositoryUrl)
                } else {
                    null
                }
            }
            .distinctBy { it.repositoryUrl }
        if (githubRemotes.size > 1) {
            val details = githubRemotes.joinToString(", ") { "${it.name}=${it.url}" }
            throw GradleException(
                "Multiple GitHub git remotes found: $details. " +
                    "Configure publish.githubPackagesRepository=owner/repo or publish.githubPackagesUrl=https://maven.pkg.github.com/owner/repo explicitly."
            )
        }
        return firstNotBlank(
            githubRemotes.singleOrNull()?.url.orEmpty(),
            remotes.firstOrNull { GitUrlNormalizer.toHttpsRepositoryUrl(it.url).isNotBlank() }?.url.orEmpty()
        )
    }

    private fun readGitRemotes(gitConfig: File): List<GitRemote> {
        val remotes = mutableListOf<GitRemote>()
        var currentRemoteName = ""
        gitConfig.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentRemoteName = Regex("""^\[remote "(.+)"\]$""")
                    .matchEntire(trimmed)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            } else if (currentRemoteName.isNotBlank() && trimmed.startsWith("url =")) {
                val url = trimmed.substringAfter("url =").trim()
                if (url.isNotBlank()) {
                    remotes += GitRemote(currentRemoteName, url)
                }
            }
        }
        return remotes
    }

    private data class GitRemote(
        val name: String,
        val url: String,
        val repositoryUrl: String = ""
    )

    private fun findGitConfigs(projectDir: File): List<File> {
        val gitPath = File(projectDir, ".git")
        if (gitPath.isDirectory) {
            return listOfNotNull(File(gitPath, "config").takeIf { it.exists() })
        }
        if (!gitPath.isFile) {
            return emptyList()
        }
        val gitDir = gitPath.readLines()
            .firstOrNull { it.startsWith("gitdir:") }
            ?.substringAfter("gitdir:")
            ?.trim()
            ?: return emptyList()
        val resolvedGitDir = File(gitDir).takeIf { it.isAbsolute } ?: File(projectDir, gitDir)
        val commonDir = File(resolvedGitDir, "commondir")
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.let { value ->
                File(value).takeIf { it.isAbsolute } ?: File(resolvedGitDir, value)
            }

        return listOfNotNull(
            File(resolvedGitDir, "config").takeIf { it.exists() },
            commonDir?.let { File(it, "config").takeIf { config -> config.exists() } }
        ).distinctBy { it.absolutePath }
    }

    private fun scmConnectionFromUrl(scmUrl: String): String {
        return GitUrlNormalizer.toScmConnection(scmUrl)
    }

    private fun scmDeveloperConnectionFromUrl(scmUrl: String): String {
        return GitUrlNormalizer.toScmDeveloperConnection(scmUrl)
    }

    private fun projectProperty(project: Project, name: String): String {
        return project.findProperty(name)?.toString()?.trim().orEmpty()
    }

    private fun commandLineProjectProperty(project: Project, name: String): String {
        return project.gradle.startParameter.projectProperties[name]?.trim().orEmpty()
    }

    private fun explicitPublishInfoValue(publishInfo: PublishInfo, fieldName: String, value: String): String {
        return if (publishInfo.isExplicit(fieldName)) value else ""
    }

    private fun environment(name: String): String {
        return System.getenv(name)?.trim().orEmpty()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun String?.toBooleanLenient(): Boolean {
        return this?.equals("true", ignoreCase = true) == true || this == "1" || this?.equals("yes", ignoreCase = true) == true
    }

    private fun String.camelToUpperSnake(): String {
        return replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    }

}
