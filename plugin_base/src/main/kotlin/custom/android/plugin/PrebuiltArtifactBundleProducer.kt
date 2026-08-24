package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

object PrebuiltArtifactBundleProducer {
    fun prepare(project: Project, path: String, expectedVersion: String? = null, requireCentral: Boolean = false): PreparedArtifactBundle {
        if (path.isBlank()) throw GradleException("artifactBundlePath is required when artifactSource=prebuilt")
        val candidate = File(path)
        if (candidate.isAbsolute) throw GradleException("artifactBundlePath must be relative to the project root")
        val root = File(project.rootProject.projectDir, path).canonicalFile
        val workspace = project.rootProject.projectDir.canonicalFile
        if (!root.toPath().startsWith(workspace.toPath())) throw GradleException("artifactBundlePath must stay inside the project workspace")
        if (!root.isDirectory) throw GradleException("Artifact bundle directory does not exist: $path")
        val bundle = ArtifactBundleManifestCodec.read(root)
        ArtifactBundleValidator.validate(bundle, expectedVersion, requireCentral)
        return bundle
    }
}
