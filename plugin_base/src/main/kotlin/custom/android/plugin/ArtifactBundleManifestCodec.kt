package custom.android.plugin

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File

object ArtifactBundleManifestCodec {
    private val json = JsonSlurper()

    fun read(bundleDirectory: File): PreparedArtifactBundle {
        val manifest = File(bundleDirectory, "publish-artifacts.json")
        if (!manifest.isFile) {
            throw GradleException("Missing prebuilt artifact manifest: ${manifest.path}")
        }
        val root = json.parse(manifest) as? Map<*, *>
            ?: throw GradleException("publish-artifacts.json must contain a JSON object")
        val schema = (root["schemaVersion"] as? Number)?.toInt()
            ?: throw GradleException("publish-artifacts.json requires numeric schemaVersion")
        if (schema != 1) {
            throw GradleException("Unsupported publish-artifacts.json schemaVersion: $schema")
        }
        val publications = (root["publications"] as? List<*>)?.mapIndexed { index, value ->
            val item = value as? Map<*, *> ?: throw GradleException("publications[$index] must be an object")
            val files = (item["files"] as? List<*>)?.mapIndexed { fileIndex, fileValue ->
                val file = fileValue as? Map<*, *> ?: throw GradleException(
                    "publications[$index].files[$fileIndex] must be an object"
                )
                ArtifactBundleFile(
                    role = required(file, "role", "publications[$index].files[$fileIndex]"),
                    path = required(file, "path", "publications[$index].files[$fileIndex]"),
                    sha256 = required(file, "sha256", "publications[$index].files[$fileIndex]").lowercase(),
                    size = (file["size"] as? Number)?.toLong()
                )
            } ?: throw GradleException("publications[$index].files is required")
            ArtifactBundlePublication(
                name = (item["name"]?.toString().orEmpty()).ifBlank { "EnterPublish" },
                groupId = required(item, "groupId", "publications[$index]"),
                artifactId = required(item, "artifactId", "publications[$index]"),
                version = required(item, "version", "publications[$index]"),
                packaging = required(item, "packaging", "publications[$index]").lowercase(),
                files = files
            )
        } ?: throw GradleException("publish-artifacts.json requires publications")
        if (publications.isEmpty()) throw GradleException("publish-artifacts.json publications must not be empty")
        return PreparedArtifactBundle(schema, bundleDirectory, publications)
    }

    fun write(bundle: PreparedArtifactBundle, manifest: File = File(bundle.rootDirectory, "publish-artifacts.json")) {
        manifest.parentFile?.mkdirs()
        val publications = bundle.publications.joinToString(",\n") { publication ->
            val files = publication.files.joinToString(",\n") { file ->
                "        {\"role\":\"${escape(file.role)}\",\"path\":\"${escape(file.path)}\"," +
                    "\"sha256\":\"${escape(file.sha256)}\",\"size\":${file.size ?: 0}}"
            }
            "    {\n" +
                "      \"name\": \"${escape(publication.name)}\",\n" +
                "      \"groupId\": \"${escape(publication.groupId)}\",\n" +
                "      \"artifactId\": \"${escape(publication.artifactId)}\",\n" +
                "      \"version\": \"${escape(publication.version)}\",\n" +
                "      \"packaging\": \"${escape(publication.packaging)}\",\n" +
                "      \"files\": [\n$files\n      ]\n" +
                "    }"
        }
        manifest.writeText("{\n  \"schemaVersion\": 1,\n  \"publications\": [\n$publications\n  ]\n}\n")
    }

    private fun required(map: Map<*, *>, key: String, context: String): String {
        return map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("$context requires $key")
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
