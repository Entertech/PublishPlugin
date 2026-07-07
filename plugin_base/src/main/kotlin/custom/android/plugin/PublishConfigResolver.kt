package custom.android.plugin

import custom.android.plugin.config.CentralPublishConfig
import custom.android.plugin.config.CentralPublishConfigLoader
import custom.android.plugin.config.GitUrlNormalizer
import custom.android.plugin.config.PomMetadataDefaults
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

    data class CentralCredentials(
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

    fun centralPublishConfigFile(project: Project): File {
        val configuredPath = projectProperty(project, "publishConfig")
        if (configuredPath.isBlank()) {
            return project.rootProject.file("local.properties")
        }
        val configuredFile = File(configuredPath)
        return if (configuredFile.isAbsolute) configuredFile else project.rootProject.file(configuredPath)
    }

    fun loadCentralPublishProperties(project: Project): CentralPublishConfig {
        return CentralPublishConfigLoader.load(centralPublishConfigFile(project))
    }

    fun resolveRemotePublishMode(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "remotePublishMode"),
            environment("REMOTE_PUBLISH_MODE"),
            publishInfo.remotePublishMode
        ).ifBlank { MODE_CENTRAL }
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
            loadCentralPublishProperties(project).centralRepositoryName,
            "CentralStaging"
        )
    }

    fun resolveCentralNamespace(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralNamespace"),
            environment("CENTRAL_NAMESPACE"),
            explicitPublishInfoValue(publishInfo, "centralNamespace", publishInfo.centralNamespace),
            loadCentralPublishProperties(project).centralNamespace,
            publishInfo.centralNamespace
        )
    }

    fun resolveCentralPublishingType(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralPublishingType"),
            environment("CENTRAL_PUBLISHING_TYPE"),
            explicitPublishInfoValue(publishInfo, "centralPublishingType", publishInfo.centralPublishingType),
            loadCentralPublishProperties(project).centralPublishingType,
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
            loadCentralPublishProperties(project).scmUrl,
            inferScmUrl(project)
        )
    }

    fun resolveScmConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmConnection"),
            environment("SCM_CONNECTION"),
            explicitPublishInfoValue(publishInfo, "scmConnection", publishInfo.scmConnection),
            loadCentralPublishProperties(project).scmConnection,
            scmConnectionFromUrl(resolveScmUrl(project, publishInfo))
        )
    }

    fun resolveScmDeveloperConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmDeveloperConnection"),
            environment("SCM_DEVELOPER_CONNECTION"),
            explicitPublishInfoValue(publishInfo, "scmDeveloperConnection", publishInfo.scmDeveloperConnection),
            loadCentralPublishProperties(project).scmDeveloperConnection,
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
            loadCentralPublishProperties(project).valueFor(propertyName),
            defaultValue
        )
    }

    private fun inferScmUrl(project: Project): String {
        val githubRepository = environment("GITHUB_REPOSITORY")
        if (githubRepository.isNotBlank()) {
            val githubServerUrl = environment("GITHUB_SERVER_URL").ifBlank { "https://github.com" }
            return "${githubServerUrl.trimEnd('/')}/$githubRepository"
        }

        return firstNotBlank(
            environment("CI_PROJECT_URL"),
            GitUrlNormalizer.toHttpsRepositoryUrl(environment("GIT_URL")),
            GitUrlNormalizer.toHttpsRepositoryUrl(environment("BUILD_REPOSITORY_URI")),
            GitUrlNormalizer.toHttpsRepositoryUrl(readGitOriginUrl(project))
        )
    }

    private fun readGitOriginUrl(project: Project): String {
        return findGitConfigs(project.rootProject.projectDir)
            .firstNotNullOfOrNull { gitConfig ->
                var inOrigin = false
                gitConfig.readLines()
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
                    ?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
    }

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
