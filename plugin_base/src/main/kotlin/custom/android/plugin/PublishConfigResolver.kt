package custom.android.plugin

import org.gradle.api.Project
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

    fun resolveText(project: Project, propertyName: String, publishInfoValue: String, defaultValue: String = ""): String {
        return firstNotBlank(
            projectProperty(project, propertyName),
            environment(propertyName.camelToUpperSnake()),
            publishInfoValue,
            defaultValue
        )
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
}
