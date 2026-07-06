package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.net.URI
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
            publishInfo.centralRepositoryName,
            "CentralStaging"
        )
    }

    fun resolveCentralNamespace(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralNamespace"),
            environment("CENTRAL_NAMESPACE"),
            publishInfo.centralNamespace
        )
    }

    fun resolveCentralPublishingType(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "centralPublishingType"),
            environment("CENTRAL_PUBLISHING_TYPE"),
            publishInfo.centralPublishingType,
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
            publishInfo.scmUrl,
            inferScmUrl(project)
        )
    }

    fun resolveScmConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmConnection"),
            environment("SCM_CONNECTION"),
            publishInfo.scmConnection,
            scmConnectionFromUrl(resolveScmUrl(project, publishInfo))
        )
    }

    fun resolveScmDeveloperConnection(project: Project, publishInfo: PublishInfo): String {
        return firstNotBlank(
            projectProperty(project, "scmDeveloperConnection"),
            environment("SCM_DEVELOPER_CONNECTION"),
            publishInfo.scmDeveloperConnection,
            scmDeveloperConnectionFromUrl(resolveScmUrl(project, publishInfo))
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

    private fun inferScmUrl(project: Project): String {
        val githubRepository = environment("GITHUB_REPOSITORY")
        if (githubRepository.isNotBlank()) {
            val githubServerUrl = environment("GITHUB_SERVER_URL").ifBlank { "https://github.com" }
            return "${githubServerUrl.trimEnd('/')}/$githubRepository"
        }

        return firstNotBlank(
            environment("CI_PROJECT_URL"),
            normalizeGitRemoteUrl(environment("GIT_URL")),
            normalizeGitRemoteUrl(environment("BUILD_REPOSITORY_URI")),
            normalizeGitRemoteUrl(readGitOriginUrl(project))
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
        val scmParts = parseScmUrl(scmUrl) ?: return ""
        return "scm:git:git://${scmParts.host}/${scmParts.path}.git"
    }

    private fun scmDeveloperConnectionFromUrl(scmUrl: String): String {
        val scmParts = parseScmUrl(scmUrl) ?: return ""
        return "scm:git:ssh://git@${scmParts.host}/${scmParts.path}.git"
    }

    private fun parseScmUrl(scmUrl: String): ScmParts? {
        val normalizedUrl = normalizeGitRemoteUrl(scmUrl)
        if (normalizedUrl.isBlank()) {
            return null
        }
        return try {
            val uri = URI(normalizedUrl)
            val host = uri.host.orEmpty()
            val path = uri.path.trim('/').removeSuffix(".git")
            if (host.isBlank() || path.isBlank()) {
                null
            } else {
                ScmParts(host, path)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeGitRemoteUrl(remoteUrl: String): String {
        val trimmed = remoteUrl.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        val sshMatch = Regex("^git@([^:]+):(.+?)(\\.git)?$").matchEntire(trimmed)
        if (sshMatch != null) {
            return "https://${sshMatch.groupValues[1]}/${sshMatch.groupValues[2].removeSuffix(".git")}"
        }
        val sshUriMatch = Regex("^ssh://git@([^/]+)/(.+?)(\\.git)?$").matchEntire(trimmed)
        if (sshUriMatch != null) {
            return "https://${sshUriMatch.groupValues[1]}/${sshUriMatch.groupValues[2].removeSuffix(".git")}"
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.removeSuffix(".git")
        }
        return ""
    }

    private fun projectProperty(project: Project, name: String): String {
        return project.findProperty(name)?.toString()?.trim().orEmpty()
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

    private data class ScmParts(
        val host: String,
        val path: String
    )
}
