package custom.android.plugin.config

import java.io.File

object PublishConfigTemplateWriter {
    private val publishLineRegex = Regex("""^\s*(?:publish|centralPublish)\.([A-Za-z0-9_.-]+)\s*=(.*)$""")
    private val primaryPublishLineRegex = Regex("""^\s*publish\.([A-Za-z0-9_.-]+)\s*=(.*)$""")
    private val legacyPublishLineRegex = Regex("""^\s*centralPublish\.([A-Za-z0-9_.-]+)\s*=(.*)$""")

    private val entries = listOf(
        Entry("githubRepo", "# GitHub repository in owner/repo format. Blank tries gh repo view, then git remote origin."),
        Entry(
            "dryRun",
            "# Dry-run switch.\n# true: Print planned files and secret names only. Do not write files or call gh secret set.\n# false/blank: Run normally."
        ),
        Entry(
            "publishTarget",
            "# Generated workflow publish target.\n# Blank/default: github_packages.\n# Supported values: github_packages, central, all."
        ),
        Entry("githubPackagesRepository", "# GitHub Packages repository in owner/repo format. Blank uses the workflow caller repository."),
        Entry("githubPackagesUrl", "# Optional full GitHub Packages Maven repository URL. Blank derives it from githubPackagesRepository."),
        Entry("centralNamespace", "# Sonatype Central namespace. Blank uses plugin default cn.entertech."),
        Entry(
            "centralPublishingType",
            "# Central deployment publishing type.\n# user_managed: Upload and validate, then leave the deployment in Central Portal for manual Publish.\n# automatic: Upload and validate, then Central tries to publish to Maven Central automatically."
        ),
        Entry("centralRepositoryName", "# Central staging repository name. Blank uses plugin default CentralStaging."),
        Entry("pomInceptionYear", "# POM inception year. Blank uses the current year."),
        Entry("licenseName", "# POM license name. Blank uses plugin default The Apache License, Version 2.0."),
        Entry("licenseUrl", "# POM license URL. Blank uses plugin default https://www.apache.org/licenses/LICENSE-2.0.txt."),
        Entry("licenseDistribution", "# POM license distribution. Blank uses plugin default repo."),
        Entry("developerId", "# POM developer id. Blank uses plugin default Entertech."),
        Entry("developerName", "# POM developer name. Blank uses plugin default Entertech."),
        Entry("developerEmail", "# POM developer email. Blank uses plugin default developer@entertech.cn."),
        Entry("developerOrganization", "# POM developer organization. Blank uses plugin default Entertech."),
        Entry("developerOrganizationUrl", "# POM developer organization URL. Blank uses plugin default https://github.com/Entertech."),
        Entry("developerUrl", "# POM developer URL. Blank uses plugin default https://github.com/Entertech."),
        Entry("scmUrl", "# SCM browser URL. Blank lets the plugin infer it from CI or git remote origin."),
        Entry("scmConnection", "# SCM connection. Blank may be inferred from scmUrl, for example scm:git:https://github.com/owner/repo.git."),
        Entry("scmDeveloperConnection", "# SCM developer connection. Blank may be inferred from scmUrl, for example scm:git:ssh://git@github.com/owner/repo.git."),
        Entry(
            "githubSecrets",
            "# Whether to configure GitHub repository secrets.\n# true: Call gh secret set for Central token and GPG signing secrets.\n# false/blank: Do not process GitHub secrets."
        ),
        Entry(
            "overwriteGithubSecrets",
            "# Whether to overwrite existing repository secrets.\n# true: Overwrite existing secrets.\n# false/blank: Reuse existing secrets to avoid replacing CI credentials by mistake."
        ),
        Entry("mavenCentralUsername", "# Central Portal User Token username. Default secret is MAVEN_CENTRAL_USERNAME."),
        Entry("mavenCentralPassword", "# Central Portal User Token password. Default secret is MAVEN_CENTRAL_PASSWORD."),
        Entry("gpgKeyFile", "# GPG ASCII private key file path. Required when GPG_KEY_CONTENTS is missing and gpgGenerate=false."),
        Entry("signingPassword", "# GPG private key password. Required when SIGNING_PASSWORD is missing or gpgGenerate=true."),
        Entry("signingKeyId", "# GPG key id, optional. Blank lets Gradle signing infer it from the private key."),
        Entry("mavenCentralUsernameSecret", "# Central username repository secret name. Blank uses MAVEN_CENTRAL_USERNAME."),
        Entry("mavenCentralPasswordSecret", "# Central password repository secret name. Blank uses MAVEN_CENTRAL_PASSWORD."),
        Entry("gpgKeySecret", "# GPG private key repository secret name. Blank uses GPG_KEY_CONTENTS."),
        Entry("signingPasswordSecret", "# GPG private key password repository secret name. Blank uses SIGNING_PASSWORD."),
        Entry("signingKeyIdSecret", "# GPG key id repository secret name. Blank uses SIGNING_KEY_ID."),
        Entry(
            "gpgGenerate",
            "# Whether this task should call local gpg to generate a new publishing signing key.\n# true: Generate a new GPG key and overwrite GPG_KEY_CONTENTS / SIGNING_PASSWORD.\n# false/blank: Do not generate. Reuse existing GPG secrets when present."
        ),
        Entry("gpgKeyType", "# GPG key type. The first phase supports RSA only."),
        Entry("gpgKeyLength", "# RSA key length. Recommended value is 4096."),
        Entry("gpgKeyExpire", "# GPG key expiration, for example 1y, 2y, or 0. 0 means no expiration and is not recommended by default."),
        Entry("gpgName", "# GPG uid name, for example Entertech."),
        Entry("gpgEmail", "# GPG uid email, for example developer@entertech.cn."),
        Entry("gpgComment", "# GPG uid comment, for example Central Publish Signing Key."),
        Entry(
            "githubActions",
            "# Whether to generate a GitHub Actions workflow.\n# true: Generate or update the workflow specified by workflowPath.\n# false/blank: Do not process workflow files."
        ),
        Entry("workflowPath", "# GitHub Actions workflow file path. Blank uses the current module name, for example .github/workflows/publish-demo-lib.yml."),
        Entry("workflowUses", "# Reusable workflow reference, for example Entertech/PublishPlugin/.github/workflows/publish.yml@main.")
    )

    fun writeTemplate(rootDir: File, configFile: File, overwrite: Boolean) {
        configFile.parentFile?.mkdirs()
        val existing = if (configFile.exists()) configFile.readText() else ""
        val existingValues = existingPublishValues(existing)
        val existingKeys = existingValues.keys

        val baseContent = if (overwrite) {
            stripPublishTemplate(existing)
        } else {
            existing.trimEnd()
        }
        val keysToWrite = if (overwrite) entries else entries.filter { it.key !in existingKeys }
        val template = keysToWrite.joinToString(System.lineSeparator() + System.lineSeparator()) { entry ->
            val value = if (overwrite) existingValues[entry.key].orEmpty() else ""
            "${entry.comment}${System.lineSeparator()}publish.${entry.key}=$value"
        }
        val newContent = listOf(baseContent, template)
            .filter { it.isNotBlank() }
            .joinToString(System.lineSeparator() + System.lineSeparator()) + System.lineSeparator()
        configFile.writeText(newContent)
        GitSafetyChecker.ensureIgnored(rootDir, configFile)
    }

    private fun existingPublishValues(existing: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        existing.lineSequence().forEach { line ->
            legacyPublishLineRegex.find(line)?.let { match ->
                values.putIfAbsent(match.groupValues[1], normalizeValue(match.groupValues[1], match.groupValues[2]))
            }
        }
        existing.lineSequence().forEach { line ->
            primaryPublishLineRegex.find(line)?.let { match ->
                values[match.groupValues[1]] = normalizeValue(match.groupValues[1], match.groupValues[2])
            }
        }
        return values
    }

    private fun normalizeValue(key: String, value: String): String {
        return when (key) {
            "workflowUses" -> GitHubActionsWorkflowWriter.normalizeWorkflowUses(value)
            else -> value
        }
    }

    private fun stripPublishTemplate(existing: String): String {
        val kept = mutableListOf<String>()
        val pendingCommentsOrBlankLines = mutableListOf<String>()
        existing.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") || line.isBlank() -> pendingCommentsOrBlankLines.add(line)
                publishLineRegex.matches(line) -> pendingCommentsOrBlankLines.clear()
                else -> {
                    kept.addAll(pendingCommentsOrBlankLines)
                    pendingCommentsOrBlankLines.clear()
                    kept.add(line)
                }
            }
        }
        kept.addAll(pendingCommentsOrBlankLines)
        return kept.joinToString(System.lineSeparator()).trimEnd()
    }

    private data class Entry(val key: String, val comment: String)
}
