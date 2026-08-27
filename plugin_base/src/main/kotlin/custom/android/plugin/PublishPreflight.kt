package custom.android.plugin

import org.gradle.api.Project
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

object PublishPreflight {
    fun run(
        project: Project,
        publishInfo: PublishInfo,
        target: ExplicitPublishTarget,
        publications: List<PublishValidationPublication>
    ): List<PublishPreflightResult> {
        val enabled = project.findProperty("publishPreflight")?.toString()?.ifBlank { "true" }?.toBoolean() ?: true
        if (!enabled) {
            return providers(project, target).map { PublishPreflightResult(it, "skipped", "disabled by publishPreflight") }
        }
        val allowExisting = project.findProperty("allowExistingVersion").toString().toBoolean()
        val config = PublishRuntimeConfig(project)
        return providers(project, target).map { provider ->
            if (provider == "central" &&
                PublishConfigResolver.resolveCentralUploadMode(project, publishInfo) == "portalApi" &&
                !PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)
            ) {
                PublishPreflightResult(
                    provider,
                    "unsupported",
                    "Central Publisher API has no side-effect-free coordinate existence or token-permission endpoint"
                )
            } else {
                val repository = if (provider == "github_packages") {
                    PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, config.properties)
                } else {
                    PublishConfigResolver.resolveCentralRepositoryUrl(project, publishInfo)
                }
                val credentials = if (provider == "github_packages") {
                    PublishConfigResolver.resolveGitHubPackagesCredentials(project, publishInfo, config.properties)
                } else {
                    val central = PublishConfigResolver.resolveCentralCredentials(project, publishInfo, config.properties)
                    PublishConfigResolver.RepositoryCredentials(central.username, central.password)
                }
                checkMavenRepository(provider, repository, credentials, publications, allowExisting)
            }
        }
    }

    private fun providers(project: Project, target: ExplicitPublishTarget): List<String> = when (target) {
        ExplicitPublishTarget.LOCAL -> emptyList()
        ExplicitPublishTarget.GITHUB_PACKAGES -> listOf("github_packages")
        ExplicitPublishTarget.CENTRAL -> listOf("central")
        ExplicitPublishTarget.ALL -> project.extensions.findByType(PublishRepositories::class.java)
            ?.enabledRemoteProviderIds().orEmpty().map {
                if (it == PublishConfigResolver.MODE_GITHUB_PACKAGES) "github_packages" else it
            }
    }

    fun checkMavenRepository(
        provider: String,
        repository: String,
        credentials: PublishConfigResolver.RepositoryCredentials,
        publications: List<PublishValidationPublication>,
        allowExisting: Boolean
    ): PublishPreflightResult {
        if (publications.isEmpty()) return PublishPreflightResult(provider, "failed", "no publication coordinates", false)
        return try {
            val existing = publications.filter { publication ->
                val base = repository.trimEnd('/')
                val pom = "$base/${publication.groupId.replace('.', '/')}/${publication.artifactId}/" +
                    "${publication.version}/${publication.artifactId}-${publication.version}.pom"
                val connection = URI(pom).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (credentials.username.isNotBlank() || credentials.password.isNotBlank()) {
                    val raw = "${credentials.username}:${credentials.password}"
                    val basic = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
                    connection.setRequestProperty("Authorization", "Basic $basic")
                }
                try {
                    when (val status = connection.responseCode) {
                        in 200..299 -> true
                        404 -> false
                        401, 403 -> return PublishPreflightResult(provider, "failed", "repository rejected credentials (HTTP $status)")
                        405 -> return PublishPreflightResult(provider, "unsupported", "repository does not support HEAD checks")
                        in 500..599 -> return PublishPreflightResult(provider, "failed", "repository unavailable (HTTP $status)", true)
                        else -> return PublishPreflightResult(provider, "failed", "unexpected repository response (HTTP $status)")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            when {
                existing.isEmpty() -> PublishPreflightResult(provider, "passed", "target versions do not exist")
                allowExisting -> PublishPreflightResult(
                    provider,
                    "passed",
                    "existing versions allowed explicitly: ${existing.joinToString { "${it.groupId}:${it.artifactId}:${it.version}" }}"
                )
                else -> PublishPreflightResult(
                    provider,
                    "failed",
                    "version already exists: ${existing.joinToString { "${it.groupId}:${it.artifactId}:${it.version}" }}"
                )
            }
        } catch (error: Exception) {
            PublishPreflightResult(provider, "failed", "repository check failed: ${sanitize(error.message.orEmpty())}", true)
        }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(password|token|secret|authorization)=?[^\\s,}]*"), "$1=***")
        .take(500)
}
