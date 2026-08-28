package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.util.Properties

enum class PublishValidationLevel(val includesCredentials: Boolean) {
    STRUCTURE(false),
    CREDENTIALS(true),
    REMOTE(true);

    companion object {
        fun parse(value: String?, default: PublishValidationLevel): PublishValidationLevel {
            if (value.isNullOrBlank()) return default
            return when (value.trim().lowercase()) {
                "structure" -> STRUCTURE
                "credentials" -> CREDENTIALS
                "remote" -> REMOTE
                else -> throw IllegalArgumentException(
                    "publishValidationLevel only supports structure, credentials, or remote, but was $value"
                )
            }
        }
    }
}

object PublishCredentialSources {
    fun summarize(
        project: Project,
        target: ExplicitPublishTarget,
        localProperties: Properties = PublishRuntimeConfig(project).properties
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        if (target == ExplicitPublishTarget.GITHUB_PACKAGES || target == ExplicitPublishTarget.ALL) {
            put("githubPackages.username", source(
                project,
                localProperties,
                projectKeys = listOf("githubPackagesUsername", "gpr.user"),
                environmentKeys = listOf("GITHUB_PACKAGES_USER", "GITHUB_ACTOR", "USERNAME"),
                localKeys = listOf("publish.local.githubPackages.username", "githubPackagesUsername")
            ))
            put("githubPackages.token", source(
                project,
                localProperties,
                projectKeys = listOf("githubPackagesPassword", "gpr.key"),
                environmentKeys = listOf("GITHUB_PACKAGES_TOKEN", "GITHUB_TOKEN", "TOKEN"),
                localKeys = listOf("publish.local.githubPackages.token", "githubPackagesPassword")
            ))
        }
        if (target == ExplicitPublishTarget.CENTRAL || target == ExplicitPublishTarget.ALL) {
            put("central.username", source(
                project,
                localProperties,
                projectKeys = listOf("centralUsername", "mavenCentralUsername"),
                environmentKeys = listOf("CENTRAL_USERNAME", "MAVEN_CENTRAL_USERNAME"),
                localKeys = listOf("publish.local.central.username", "centralUsername", "mavenCentralUsername")
            ))
            put("central.password", source(
                project,
                localProperties,
                projectKeys = listOf("centralPassword", "mavenCentralPassword"),
                environmentKeys = listOf("CENTRAL_PASSWORD", "MAVEN_CENTRAL_PASSWORD"),
                localKeys = listOf("publish.local.central.password", "centralPassword", "mavenCentralPassword")
            ))
            put("signing.key", signingKeySource(project, localProperties))
            put("signing.password", source(
                project,
                localProperties,
                projectKeys = listOf("signingInMemoryKeyPassword", "signingPassword"),
                environmentKeys = listOf("SIGNING_IN_MEMORY_KEY_PASSWORD", "SIGNING_PASSWORD"),
                localKeys = listOf("publish.local.central.signingPassword", "signingPassword")
            ))
        }
    }

    private fun signingKeySource(project: Project, localProperties: Properties): String {
        val direct = source(
            project,
            localProperties,
            projectKeys = listOf("signingInMemoryKey"),
            environmentKeys = listOf("SIGNING_IN_MEMORY_KEY", "GPG_KEY_CONTENTS"),
            localKeys = listOf("publish.local.central.signingKey", "signingInMemoryKey", "gpgKeyContents")
        )
        if (direct != "missing") return direct
        val keyFile = localProperties.getProperty("publish.local.central.signingKeyFile")
            ?.trim()
            .orEmpty()
        if (keyFile.isBlank()) return "missing"
        val file = File(keyFile).let { if (it.isAbsolute) it else project.rootProject.file(keyFile) }
        return if (file.isFile) "local_file" else "missing"
    }

    private fun source(
        project: Project,
        localProperties: Properties,
        projectKeys: List<String>,
        environmentKeys: List<String>,
        localKeys: List<String>
    ): String {
        if (projectKeys.any { !project.findProperty(it)?.toString().isNullOrBlank() }) return "gradle_property"
        if (environmentKeys.any { !System.getenv(it).isNullOrBlank() }) return "environment"
        if (localKeys.any { !localProperties.getProperty(it).isNullOrBlank() }) return "local_file"
        return "missing"
    }
}
