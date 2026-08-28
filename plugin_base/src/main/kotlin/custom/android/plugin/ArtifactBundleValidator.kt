package custom.android.plugin

import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest

object ArtifactBundleValidator {
    private val roles = setOf("main", "pom", "gradle_module", "sources", "javadoc", "signature", "checksum", "plugin_marker")
    private val packaging = setOf("aar", "jar", "pom")

    fun validate(bundle: PreparedArtifactBundle, expectedVersion: String? = null, requireCentral: Boolean = false) {
        val root = bundle.rootDirectory.canonicalFile
        val errors = mutableListOf<String>()
        for (publication in bundle.publications) {
            if (publication.groupId.isBlank() || publication.groupId.contains('/') || publication.groupId.contains("..")) {
                errors += "${publication.name}: invalid groupId ${publication.groupId}"
            }
            if (publication.artifactId.isBlank() || publication.artifactId.contains('/') || publication.artifactId.contains("..")) {
                errors += "${publication.name}: invalid artifactId ${publication.artifactId}"
            }
            if (publication.version.isBlank() || publication.version.contains('/') || publication.version.contains("..")) {
                errors += "${publication.name}: invalid version ${publication.version}"
            }
            if (publication.packaging !in packaging) errors += "${publication.name}: unsupported packaging ${publication.packaging}"
            if (expectedVersion != null && publication.version != expectedVersion) {
                errors += "${publication.name}: manifest version ${publication.version} does not match $expectedVersion"
            }
            val seenRoles = mutableSetOf<String>()
            for (file in publication.files) {
                if (file.role !in roles) errors += "${publication.name}: unsupported file role ${file.role}"
                if (!Regex("[0-9a-fA-F]{64}").matches(file.sha256)) {
                    errors += "${publication.name}: invalid SHA-256 for ${file.path}"
                }
                if (file.size != null && file.size < 0) {
                    errors += "${publication.name}: negative size for ${file.path}"
                }
                if (file.role !in setOf("signature", "checksum") && !seenRoles.add(file.role)) {
                    errors += "${publication.name}: duplicate file role ${file.role}"
                } else {
                    seenRoles.add(file.role)
                }
                val relative = File(file.path)
                if (relative.isAbsolute || relative.path.split(File.separatorChar, '/', '\\').any { it == ".." }) {
                    errors += "${publication.name}: file path escapes bundle: ${file.path}"
                    continue
                }
                val actual = File(root, file.path).canonicalFile
                if (!actual.toPath().startsWith(root.toPath())) {
                    errors += "${publication.name}: file path escapes bundle: ${file.path}"
                    continue
                }
                if (!actual.isFile) {
                    errors += "${publication.name}: missing ${file.path}"
                    continue
                }
                if (file.size != null && file.size != actual.length()) errors += "${publication.name}: size mismatch for ${file.path}"
                val digest = sha256(actual)
                if (!digest.equals(file.sha256, ignoreCase = true)) errors += "${publication.name}: SHA-256 mismatch for ${file.path}"
                if (file.role == "main" && actual.extension.lowercase() != publication.packaging) {
                    errors += "${publication.name}: main file extension does not match packaging"
                }
                if (file.role == "pom" && actual.extension.lowercase() != "pom") {
                    errors += "${publication.name}: pom file must use .pom extension"
                }
                if (file.role == "gradle_module" && actual.extension.lowercase() != "module") {
                    errors += "${publication.name}: gradle_module file must use .module extension"
                }
                if (file.role == "sources" && !actual.name.endsWith("-sources.jar")) {
                    errors += "${publication.name}: sources file must end with -sources.jar"
                }
                if (file.role == "javadoc" && !actual.name.endsWith("-javadoc.jar")) {
                    errors += "${publication.name}: javadoc file must end with -javadoc.jar"
                }
                if (file.role == "signature" && !actual.name.endsWith(".asc")) {
                    errors += "${publication.name}: signature file must end with .asc"
                }
                if (file.role == "checksum" && !actual.name.endsWith(".sha256")) {
                    errors += "${publication.name}: checksum file must end with .sha256"
                }
            }
            if (publication.packaging != "pom" && "main" !in seenRoles) {
                errors += "${publication.name}: missing main file"
            }
            if (publication.packaging == "pom" && "plugin_marker" !in seenRoles && "pom" !in seenRoles) {
                errors += "${publication.name}: pom publication requires plugin_marker or pom file"
            }
            if ("pom" !in seenRoles && publication.packaging != "pom") {
                errors += "${publication.name}: missing pom file"
            }
            if (requireCentral) {
                if (publication.packaging != "pom") {
                    if ("sources" !in seenRoles) errors += "${publication.name}: Central requires sources file"
                    if ("javadoc" !in seenRoles) errors += "${publication.name}: Central requires javadoc file"
                }
                if ("signature" !in seenRoles) errors += "${publication.name}: Central requires signature file"
            }
        }
        if (errors.isNotEmpty()) throw GradleException("Artifact bundle validation failed:\n- ${errors.joinToString("\n- ")}")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
