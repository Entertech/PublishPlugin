package custom.android.plugin.config

import java.io.File
import java.util.Properties

object PublishConfigLoader {
    private const val PREFIX = "publish."
    private const val LEGACY_PREFIX = "centralPublish."

    private val componentFields = setOf(
        "groupId",
        "artifactId",
        "version",
        "pluginId",
        "implementationClass",
        "pomName",
        "pomDescription",
        "pomUrl"
    )

    private val moduleFields = setOf("modules")

    fun load(file: File): PublishConfig {
        if (!file.exists()) {
            return PublishConfig()
        }
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        return load(properties)
    }

    fun load(properties: Properties): PublishConfig {
        val legacyValues = mutableMapOf<String, String>()
        val primaryValues = mutableMapOf<String, String>()
        properties.stringPropertyNames().forEach { rawKey ->
            val rawValue = properties.getProperty(rawKey)?.trim().orEmpty()
            val key = configKey(rawKey)
            validateUnsupportedField(rawKey, key)

            if (rawValue.isBlank()) {
                return@forEach
            }
            when {
                rawKey.startsWith(PREFIX) -> {
                    val normalizedKey = normalizeKey(key)
                    primaryValues[normalizedKey] = normalizeValue(normalizedKey, rawValue)
                }
                rawKey.startsWith(LEGACY_PREFIX) -> {
                    val normalizedKey = normalizeKey(key)
                    legacyValues[normalizedKey] = normalizeValue(normalizedKey, rawValue)
                }
            }
        }

        val values = legacyValues + primaryValues
        validatePublishTarget(values["publishTarget"].orEmpty())
        validatePublishingType(values["centralPublishingType"].orEmpty())
        return PublishConfig(
            githubRepo = values["githubRepo"].orEmpty(),
            dryRun = values["dryRun"].orEmpty(),
            publishTarget = normalizePublishTarget(values["publishTarget"].orEmpty()),
            githubPackagesRepository = values["githubPackagesRepository"].orEmpty(),
            githubPackagesUrl = values["githubPackagesUrl"].orEmpty(),
            centralNamespace = values["centralNamespace"].orEmpty(),
            centralPublishingType = values["centralPublishingType"].orEmpty(),
            centralRepositoryName = values["centralRepositoryName"].orEmpty(),
            pomInceptionYear = values["pomInceptionYear"].orEmpty(),
            licenseName = values["licenseName"].orEmpty(),
            licenseUrl = values["licenseUrl"].orEmpty(),
            licenseDistribution = values["licenseDistribution"].orEmpty(),
            developerId = values["developerId"].orEmpty(),
            developerName = values["developerName"].orEmpty(),
            developerEmail = values["developerEmail"].orEmpty(),
            developerOrganization = values["developerOrganization"].orEmpty(),
            developerOrganizationUrl = values["developerOrganizationUrl"].orEmpty(),
            developerUrl = values["developerUrl"].orEmpty(),
            scmUrl = values["scmUrl"].orEmpty(),
            scmConnection = values["scmConnection"].orEmpty(),
            scmDeveloperConnection = values["scmDeveloperConnection"].orEmpty(),
            githubSecrets = values["githubSecrets"].orEmpty(),
            overwriteGithubSecrets = values["overwriteGithubSecrets"].orEmpty(),
            mavenCentralUsername = values["mavenCentralUsername"].orEmpty(),
            mavenCentralPassword = values["mavenCentralPassword"].orEmpty(),
            gpgKeyFile = values["gpgKeyFile"].orEmpty(),
            signingPassword = values["signingPassword"].orEmpty(),
            signingKeyId = values["signingKeyId"].orEmpty(),
            mavenCentralUsernameSecret = values["mavenCentralUsernameSecret"].orEmpty(),
            mavenCentralPasswordSecret = values["mavenCentralPasswordSecret"].orEmpty(),
            gpgKeySecret = values["gpgKeySecret"].orEmpty(),
            signingPasswordSecret = values["signingPasswordSecret"].orEmpty(),
            signingKeyIdSecret = values["signingKeyIdSecret"].orEmpty(),
            gpgGenerate = values["gpgGenerate"].orEmpty(),
            gpgKeyType = values["gpgKeyType"].orEmpty(),
            gpgKeyLength = values["gpgKeyLength"].orEmpty(),
            gpgKeyExpire = values["gpgKeyExpire"].orEmpty(),
            gpgName = values["gpgName"].orEmpty(),
            gpgEmail = values["gpgEmail"].orEmpty(),
            gpgComment = values["gpgComment"].orEmpty(),
            githubActions = values["githubActions"].orEmpty(),
            workflowPath = values["workflowPath"].orEmpty(),
            workflowUses = values["workflowUses"].orEmpty()
        )
    }

    private fun validateUnsupportedField(rawKey: String, key: String) {
        if (key in componentFields || rawKey in componentFields) {
            throw IllegalArgumentException(
                "$rawKey is a component field. Move it to the current module PublishInfo block."
            )
        }
        if (key in moduleFields || rawKey in moduleFields || key.startsWith("module.") || rawKey.startsWith("module.")) {
            throw IllegalArgumentException(
                "$rawKey is not supported. Run the target module Gradle task directly instead of declaring modules."
            )
        }
    }

    private fun configKey(rawKey: String): String {
        return when {
            rawKey.startsWith(PREFIX) -> rawKey.removePrefix(PREFIX)
            rawKey.startsWith(LEGACY_PREFIX) -> rawKey.removePrefix(LEGACY_PREFIX)
            else -> rawKey
        }
    }

    private fun normalizeKey(key: String): String {
        return when (key) {
            "centralUsername" -> "mavenCentralUsername"
            "centralPassword" -> "mavenCentralPassword"
            "signingInMemoryKeyPassword" -> "signingPassword"
            "signingInMemoryKeyId" -> "signingKeyId"
            "publishTarget" -> "publishTarget"
            else -> key
        }
    }

    private fun normalizeValue(key: String, value: String): String {
        return when (key) {
            "workflowUses" -> GitHubActionsWorkflowWriter.normalizeWorkflowUses(value)
            else -> value
        }
    }

    private fun validatePublishTarget(value: String) {
        if (value.isBlank()) {
            return
        }
        val normalized = normalizePublishTarget(value)
        if (normalized == "central" || normalized == "github_packages" || normalized == "all") {
            return
        }
        throw IllegalArgumentException(
            "publishTarget only supports central, github_packages, or all, but was $value"
        )
    }

    private fun normalizePublishTarget(value: String): String {
        return when (value.trim()) {
            "githubPackages" -> "github_packages"
            else -> value.trim()
        }
    }

    private fun validatePublishingType(value: String) {
        if (value.isBlank() || value == "user_managed" || value == "automatic") {
            return
        }
        throw IllegalArgumentException(
            "centralPublishingType only supports user_managed or automatic, but was $value"
        )
    }
}
