package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

object ArtifactBundlePublisher {
    fun publishToMavenLocal(project: Project, bundle: PreparedArtifactBundle) {
        val repository = File(System.getProperty("maven.repo.local") ?: File(System.getProperty("user.home"), ".m2/repository").path)
        bundle.publications.forEach { publication ->
            val target = File(repository, "${publication.groupId.replace('.', File.separatorChar)}${File.separator}${publication.artifactId}${File.separator}${publication.version}")
            target.mkdirs()
            publication.files.forEach { file ->
                val source = File(bundle.rootDirectory, file.path)
                val targetName = targetName(publication, file, source)
                source.copyTo(File(target, targetName), overwrite = true)
            }
        }
        PluginLogUtil.printlnInfoInScreen("Published prebuilt artifact bundle to Maven Local")
    }

    fun publishToRemote(project: Project, bundle: PreparedArtifactBundle, baseUrl: String, username: String, password: String) {
        if (baseUrl.isBlank()) throw GradleException("Remote repository URL is required for prebuilt publishing")
        val auth = if (username.isNotBlank() || password.isNotBlank()) {
            "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        } else null
        bundle.publications.forEach { publication ->
            publication.files.forEach { file ->
                val source = File(bundle.rootDirectory, file.path)
                val url = baseUrl.trimEnd('/') + "/" + publication.groupId.replace('.', '/') + "/" + publication.artifactId + "/" + publication.version + "/" + targetName(publication, file, source)
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                if (auth != null) connection.setRequestProperty("Authorization", auth)
                source.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
                val code = connection.responseCode
                if (code !in 200..299) throw GradleException("Prebuilt upload failed for ${publication.artifactId}: HTTP $code")
                connection.disconnect()
            }
        }
        PluginLogUtil.printlnInfoInScreen("Published prebuilt artifact bundle to $baseUrl")
    }

    private fun targetName(publication: ArtifactBundlePublication, file: ArtifactBundleFile, source: File): String {
        if (file.role == "signature" || file.role == "checksum" || file.role == "plugin_marker") {
            return source.name
        }
        val extension = when (file.role) {
            "main" -> publication.packaging
            "pom" -> "pom"
            "gradle_module" -> "module"
            "sources" -> "jar"
            "javadoc" -> "jar"
            else -> File(file.path).extension.ifBlank { "bin" }
        }
        val suffix = when (file.role) {
            "main" -> ""
            "pom" -> ""
            "gradle_module" -> ""
            "sources" -> "-sources"
            "javadoc" -> "-javadoc"
            else -> ""
        }
        return "${publication.artifactId}-${publication.version}$suffix.$extension"
    }
}
