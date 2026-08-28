package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File

object ProjectArtifactBundleProducer {
    fun prepare(
        project: Project,
        publications: List<PublishValidationPublication>,
        requireCentral: Boolean
    ): PreparedArtifactBundle {
        if (publications.isEmpty()) throw GradleException("Project bundle requires at least one publication")
        val root = File(project.buildDir, "reports/publish/project-bundle").canonicalFile
        if (root.exists() && !root.deleteRecursively()) {
            throw GradleException("Unable to clean project bundle directory: ${root.path}")
        }
        root.mkdirs()
        runPreparationBuild(project, root, requireCentral)
        val prepared = PreparedArtifactBundle(
            schemaVersion = 1,
            rootDirectory = root,
            publications = publications.map { scanPublication(root, it) }
        )
        ArtifactBundleValidator.validate(prepared, requireCentral = requireCentral)
        ArtifactBundleManifestCodec.write(prepared)
        return prepared
    }

    private fun runPreparationBuild(project: Project, repository: File, requireCentral: Boolean) {
        val executable = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "gradlew.bat"
        } else {
            "gradlew"
        }
        val wrapper = File(project.rootProject.projectDir, executable)
        if (!wrapper.isFile) throw GradleException("Gradle wrapper not found: ${wrapper.path}")
        val output = ByteArrayOutputStream()
        val properties = project.gradle.startParameter.projectProperties
            .filterValues { it.isNotBlank() }
            .toMutableMap()
            .apply {
                this["publishPreparation"] = "true"
                if (requireCentral) {
                    this["centralPublish"] = "true"
                    this["publishTarget"] = "central"
                }
            }
        val result = project.exec { exec: ExecSpec ->
            exec.standardOutput = output
            exec.errorOutput = output
            exec.isIgnoreExitValue = true
            properties.forEach { (key, value) -> exec.environment("ORG_GRADLE_PROJECT_$key", value) }
            exec.commandLine(wrapper.absolutePath)
            exec.args(
                "${project.path}:publishToMavenLocal",
                "-Dmaven.repo.local=${repository.absolutePath}",
                "--no-daemon",
                "--stacktrace"
            )
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "Project artifact bundle preparation failed: ${sanitize(output.toString()).takeLast(4_000)}"
            )
        }
    }

    internal fun scanPublication(root: File, publication: PublishValidationPublication): ArtifactBundlePublication {
        val directory = File(
            root,
            "${publication.groupId.replace('.', File.separatorChar)}${File.separator}" +
                "${publication.artifactId}${File.separator}${publication.version}"
        )
        if (!directory.isDirectory) {
            throw GradleException(
                "Prepared Maven layout is missing ${publication.groupId}:${publication.artifactId}:${publication.version}"
            )
        }
        val prefix = "${publication.artifactId}-${publication.version}"
        val rawFiles = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".md5") && !it.name.endsWith(".sha1") }
            .sortedBy { it.name }
        val packaging = when {
            rawFiles.any { it.name == "$prefix.aar" } -> "aar"
            rawFiles.any { it.name == "$prefix.jar" } -> "jar"
            else -> "pom"
        }
        val files = rawFiles.filter { role(it.name, prefix, packaging) != null }
        return ArtifactBundlePublication(
            name = publication.name,
            groupId = publication.groupId,
            artifactId = publication.artifactId,
            version = publication.version,
            packaging = packaging,
            files = files.map { file ->
                ArtifactBundleFile(
                    role = requireNotNull(role(file.name, prefix, packaging)),
                    path = file.relativeTo(root).invariantSeparatorsPath,
                    sha256 = ArtifactBundleValidator.sha256(file),
                    size = file.length()
                )
            }
        )
    }

    private fun role(name: String, prefix: String, packaging: String): String? = when {
        name.endsWith(".asc") -> "signature"
        name.endsWith(".sha256") -> "checksum"
        name == "$prefix.pom" && packaging == "pom" -> "plugin_marker"
        name == "$prefix.pom" -> "pom"
        name == "$prefix.module" -> "gradle_module"
        name == "$prefix-sources.jar" -> "sources"
        name == "$prefix-javadoc.jar" -> "javadoc"
        name == "$prefix.$packaging" && packaging != "pom" -> "main"
        else -> null
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(password|token|secret|key)[^\\s,}]*"), "$1=***")
}
