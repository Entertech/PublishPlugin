package custom.android.plugin

import org.gradle.api.Project
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipFile

data class PublishSupplyChainEvidence(
    val gates: List<PublishGateResult>,
    val provenance: Map<String, String>
)

object PublishSupplyChain {
    fun collect(project: Project, bundle: PreparedArtifactBundle?): PublishSupplyChainEvidence {
        val directory = File(project.buildDir, "reports/publish").apply { mkdirs() }
        val publications = bundle?.publications.orEmpty()
        writeCycloneDx(directory, bundle)
        val gates = listOf(
            apiBaselineGate(project, bundle, directory),
            dependencyPolicyGate(project, bundle),
            trustedSourceGate(project, bundle)
        )
        val hashes = publications.flatMap { it.files }.sortedBy { it.path }
            .joinToString(",") { "${it.path}:${it.sha256}" }
        val provenance = linkedMapOf(
            "gitCommit" to gitCommit(project),
            "workflowRunId" to System.getenv("GITHUB_RUN_ID").orEmpty().ifBlank { "local" },
            "gradleVersion" to project.gradle.gradleVersion,
            "jdkVersion" to System.getProperty("java.version"),
            "artifactBundleSha256" to digest(hashes)
        )
        return PublishSupplyChainEvidence(gates, provenance)
    }

    private fun apiBaselineGate(
        project: Project,
        bundle: PreparedArtifactBundle?,
        directory: File
    ): PublishGateResult {
        if (bundle == null) return PublishGateResult("api_abi_baseline", "skipped", "no prepared bundle")
        val current = apiDump(bundle)
        File(directory, "publish-api.txt").writeText(current)
        val baselinePath = project.findProperty("publishApiBaseline")?.toString().orEmpty()
        if (baselinePath.isBlank()) return PublishGateResult("api_abi_baseline", "skipped", "publishApiBaseline is not configured")
        val baseline = project.rootProject.file(baselinePath)
        if (!baseline.isFile) return PublishGateResult("api_abi_baseline", "failed", "baseline file does not exist")
        return if (baseline.readText().trim() == current.trim()) {
            PublishGateResult("api_abi_baseline", "passed", "public API dump matches baseline")
        } else {
            PublishGateResult("api_abi_baseline", "failed", "public API dump differs from baseline; inspect publish-api.txt")
        }
    }

    private fun dependencyPolicyGate(project: Project, bundle: PreparedArtifactBundle?): PublishGateResult {
        val denied = project.findProperty("publishDeniedDependencyGroups")?.toString().orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (denied.isEmpty()) return PublishGateResult("dependency_policy", "passed", "no denied dependency groups configured")
        if (bundle == null) return PublishGateResult("dependency_policy", "skipped", "no prepared bundle")
        val violations = bundle.publications.flatMap { it.files }.filter { it.role in setOf("pom", "plugin_marker") }.filter { file ->
            val text = File(bundle.rootDirectory, file.path).takeIf { it.isFile }?.readText().orEmpty()
            denied.any { group ->
                text.contains("<groupId>$group</groupId>") || text.contains("<groupId>${escapeXml(group)}</groupId>")
            }
        }
        return if (violations.isEmpty()) PublishGateResult("dependency_policy", "passed", "dependency group policy passed")
        else PublishGateResult("dependency_policy", "failed", "denied dependency groups found")
    }

    private fun trustedSourceGate(project: Project, bundle: PreparedArtifactBundle?): PublishGateResult {
        if (bundle == null) return PublishGateResult("trusted_artifact_source", "skipped", "no prepared bundle")
        val roots = project.findProperty("trustedArtifactRoots")?.toString().orEmpty()
            .split(Regex("[${Regex.escape(File.pathSeparator)},]"))
            .map { it.trim() }.filter { it.isNotEmpty() }.map { path -> project.rootProject.file(path).canonicalFile }
        if (roots.isEmpty()) return PublishGateResult("trusted_artifact_source", "skipped", "trustedArtifactRoots is not configured")
        val trusted = roots.any { bundle.rootDirectory.canonicalFile.toPath().startsWith(it.toPath()) }
        if (!trusted) return PublishGateResult("trusted_artifact_source", "failed", "bundle root is not under trustedArtifactRoots")
        val keyringPath = project.findProperty("publishTrustedKeyring")?.toString().orEmpty()
        if (keyringPath.isBlank()) return PublishGateResult(
            "trusted_artifact_source",
            "skipped",
            "bundle root is trusted; publishTrustedKeyring is not configured for cryptographic verification"
        )
        val keyring = project.rootProject.file(keyringPath)
        if (!keyring.isFile) return PublishGateResult("trusted_artifact_source", "failed", "trusted keyring does not exist")
        val errors = verifySignatures(bundle, keyring)
        return if (errors.isEmpty()) PublishGateResult("trusted_artifact_source", "passed", "all publication signatures verified")
        else PublishGateResult("trusted_artifact_source", "failed", errors.joinToString("; ").take(500))
    }

    private fun apiDump(bundle: PreparedArtifactBundle): String = buildString {
        bundle.publications.sortedBy { "${it.groupId}:${it.artifactId}" }.forEach { publication ->
            appendLine("# ${publication.groupId}:${publication.artifactId}")
            publication.files.filter { it.role == "main" }.forEach { main ->
                val artifact = File(bundle.rootDirectory, main.path)
                val jar = when (publication.packaging) {
                    "jar" -> artifact
                    "aar" -> extractClassesJar(artifact)
                    else -> null
                }
                if (jar != null && jar.isFile) append(javap(jar))
            }
        }
    }

    private fun extractClassesJar(aar: File): File? {
        val output = File(aar.parentFile, ".${aar.nameWithoutExtension}-classes.jar")
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar") ?: return null
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        output.deleteOnExit()
        return output
    }

    private fun javap(jar: File): String {
        val classes = JarFile(jar).use { archive ->
            archive.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .filterNot { it.substringAfterLast('.').matches(Regex(".*\\$\\d+.*")) }
                .sorted().toList()
        }
        if (classes.isEmpty()) return ""
        val executable = File(System.getProperty("java.home"), "bin/javap")
        return classes.joinToString("\n") { className ->
            try {
                val process = ProcessBuilder(executable.path, "-public", "-classpath", jar.path, className)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor() == 0) output else "ERROR $className"
            } catch (_: Exception) {
                "ERROR $className"
            }
        } + "\n"
    }

    private fun verifySignatures(bundle: PreparedArtifactBundle, keyring: File): List<String> {
        val errors = mutableListOf<String>()
        bundle.publications.forEach { publication ->
            val byPath = publication.files.associateBy { it.path }
            publication.files.filter { it.role in setOf("main", "pom", "plugin_marker", "sources", "javadoc") }
                .forEach { subject ->
                    if (byPath["${subject.path}.asc"] == null) errors += "missing signature for ${subject.path}"
                }
            publication.files.filter { it.role == "signature" }.forEach { signature ->
                val signedPath = signature.path.removeSuffix(".asc")
                if (byPath[signedPath] == null) {
                    errors += "signature has no manifest subject: ${signature.path}"
                } else {
                    val process = ProcessBuilder(
                        "gpg", "--batch", "--no-default-keyring", "--keyring", keyring.path,
                        "--verify", File(bundle.rootDirectory, signature.path).path,
                        File(bundle.rootDirectory, signedPath).path
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    if (process.waitFor() != 0) errors += "signature verification failed: ${sanitize(output)}"
                }
            }
        }
        return errors
    }

    private fun writeCycloneDx(directory: File, bundle: PreparedArtifactBundle?) {
        val coordinates = linkedSetOf<Triple<String, String, String>>()
        bundle?.publications.orEmpty().forEach { publication ->
            coordinates += Triple(publication.groupId, publication.artifactId, publication.version)
            publication.files.filter { it.role in setOf("pom", "plugin_marker") }.forEach { pom ->
                val text = File(bundle!!.rootDirectory, pom.path).takeIf { it.isFile }?.readText().orEmpty()
                Regex("<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?<version>(.*?)</version>.*?</dependency>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(text).forEach { match ->
                        coordinates += Triple(
                            match.groupValues[1].trim(),
                            match.groupValues[2].trim(),
                            match.groupValues[3].trim()
                        )
                    }
            }
        }
        val components = coordinates.joinToString(",\n") { (groupId, artifactId, version) ->
            "    {\"type\": \"library\", \"group\": \"${escape(groupId)}\", " +
                "\"name\": \"${escape(artifactId)}\", \"version\": \"${escape(version)}\", " +
                "\"purl\": \"pkg:maven/${escape(groupId)}/${escape(artifactId)}@${escape(version)}\"}"
        }
        File(directory, "publish-sbom.cdx.json").writeText(
            "{\n  \"bomFormat\": \"CycloneDX\",\n  \"specVersion\": \"1.5\",\n  \"version\": 1,\n  \"components\": [\n$components\n  ]\n}\n"
        )
    }

    private fun gitCommit(project: Project): String = try {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(project.rootProject.projectDir).start()
        process.inputStream.bufferedReader().readText().trim().takeIf { process.waitFor() == 0 }.orEmpty().ifBlank { "unknown" }
    } catch (_: Exception) { "unknown" }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun sanitize(value: String): String = value.replace(Regex("(?i)(password|token|secret|key)[^\\s,}]*"), "$1=***")
}
