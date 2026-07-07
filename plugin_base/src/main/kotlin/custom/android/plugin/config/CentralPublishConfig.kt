package custom.android.plugin.config

data class CentralPublishConfig(
    val githubRepo: String = "",
    val dryRun: String = "",
    val centralNamespace: String = "",
    val centralPublishingType: String = "",
    val centralRepositoryName: String = "",
    val pomInceptionYear: String = "",
    val licenseName: String = "",
    val licenseUrl: String = "",
    val licenseDistribution: String = "",
    val developerId: String = "",
    val developerName: String = "",
    val developerEmail: String = "",
    val developerOrganization: String = "",
    val developerOrganizationUrl: String = "",
    val developerUrl: String = "",
    val scmUrl: String = "",
    val scmConnection: String = "",
    val scmDeveloperConnection: String = "",
    val githubSecrets: String = "",
    val overwriteGithubSecrets: String = "",
    val mavenCentralUsername: String = "",
    val mavenCentralPassword: String = "",
    val gpgKeyFile: String = "",
    val signingPassword: String = "",
    val signingKeyId: String = "",
    val mavenCentralUsernameSecret: String = "",
    val mavenCentralPasswordSecret: String = "",
    val gpgKeySecret: String = "",
    val signingPasswordSecret: String = "",
    val signingKeyIdSecret: String = "",
    val gpgGenerate: String = "",
    val gpgKeyType: String = "",
    val gpgKeyLength: String = "",
    val gpgKeyExpire: String = "",
    val gpgName: String = "",
    val gpgEmail: String = "",
    val gpgComment: String = "",
    val githubActions: String = "",
    val workflowPath: String = "",
    val workflowUses: String = ""
) {
    val dryRunEnabled: Boolean get() = dryRun.toBooleanLenient()
    val githubSecretsEnabled: Boolean get() = githubSecrets.toBooleanLenient()
    val overwriteGithubSecretsEnabled: Boolean get() = overwriteGithubSecrets.toBooleanLenient()
    val gpgGenerateEnabled: Boolean get() = gpgGenerate.toBooleanLenient()
    val githubActionsEnabled: Boolean get() = githubActions.toBooleanLenient()

    val effectiveMavenCentralUsernameSecret: String
        get() = mavenCentralUsernameSecret.ifBlank { "MAVEN_CENTRAL_USERNAME" }
    val effectiveMavenCentralPasswordSecret: String
        get() = mavenCentralPasswordSecret.ifBlank { "MAVEN_CENTRAL_PASSWORD" }
    val effectiveGpgKeySecret: String
        get() = gpgKeySecret.ifBlank { "GPG_KEY_CONTENTS" }
    val effectiveSigningPasswordSecret: String
        get() = signingPasswordSecret.ifBlank { "SIGNING_PASSWORD" }
    val effectiveSigningKeyIdSecret: String
        get() = signingKeyIdSecret.ifBlank { "SIGNING_KEY_ID" }

    fun valueFor(field: String): String {
        return when (field) {
            "centralNamespace" -> centralNamespace
            "centralPublishingType" -> centralPublishingType
            "centralRepositoryName" -> centralRepositoryName
            "pomInceptionYear" -> pomInceptionYear
            "licenseName" -> licenseName
            "licenseUrl" -> licenseUrl
            "licenseDistribution" -> licenseDistribution
            "developerId" -> developerId
            "developerName" -> developerName
            "developerEmail" -> developerEmail
            "developerOrganization" -> developerOrganization
            "developerOrganizationUrl" -> developerOrganizationUrl
            "developerUrl" -> developerUrl
            "scmUrl" -> scmUrl
            "scmConnection" -> scmConnection
            "scmDeveloperConnection" -> scmDeveloperConnection
            else -> ""
        }
    }
}

internal fun String?.toBooleanLenient(): Boolean {
    return this?.equals("true", ignoreCase = true) == true ||
        this == "1" ||
        this?.equals("yes", ignoreCase = true) == true
}
