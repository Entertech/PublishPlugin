package custom.android.plugin

import java.io.File

data class ArtifactBundleFile(
    val role: String,
    val path: String,
    val sha256: String,
    val size: Long? = null
)

data class ArtifactBundlePublication(
    val name: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String,
    val files: List<ArtifactBundleFile>
)

data class PreparedArtifactBundle(
    val schemaVersion: Int,
    val rootDirectory: File,
    val publications: List<ArtifactBundlePublication>
)

internal fun requiresCentralBundle(
    target: ExplicitPublishTarget,
    centralEnabled: Boolean
): Boolean = target == ExplicitPublishTarget.CENTRAL ||
    (target == ExplicitPublishTarget.ALL && centralEnabled)

enum class ArtifactSource {
    PROJECT,
    PREBUILT;

    companion object {
        fun parse(value: String?): ArtifactSource = when (value.orEmpty().trim().lowercase()) {
            "", "project" -> PROJECT
            "prebuilt" -> PREBUILT
            else -> throw IllegalArgumentException("artifactSource only supports project or prebuilt, but was $value")
        }
    }
}
