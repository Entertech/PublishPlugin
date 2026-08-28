package custom.android.plugin

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import java.io.File

/** Prints deterministic Maven Local locations after a local publication completes. */
object LocalPublishReporter {
    fun print(publications: Iterable<PublishValidationPublication>) {
        val repository = mavenLocalRepository()
        PluginLogUtil.printlnInfoInScreen("Maven Local repository: ${repository.toURI()}")
        publications.forEach { publication ->
            val directory = File(
                repository,
                "${publication.groupId.replace('.', File.separatorChar)}${File.separator}" +
                    "${publication.artifactId}${File.separator}${publication.version}"
            )
            PluginLogUtil.printlnInfoInScreen(
                "Maven Local publication: ${publication.groupId}:${publication.artifactId}:${publication.version}"
            )
            PluginLogUtil.printlnInfoInScreen("Maven Local address: ${directory.toURI()}")
            PluginLogUtil.printlnInfoInScreen(
                "dependencies {\n" +
                    "    implementation '${publication.groupId}:${publication.artifactId}:${publication.version}'\n" +
                    "}"
            )
            PluginLogUtil.printlnInfoInScreen(
                "dependencies {\n" +
                    "    implementation(\"${publication.groupId}:${publication.artifactId}:${publication.version}\")\n" +
                    "}"
            )
        }
    }

    fun print(project: Project) {
        val publishing = project.extensions.findByType(PublishingExtension::class.java) ?: return
        val publications = publishing.publications
            .withType(MavenPublication::class.java)
            .toList()
            .filter { it.name.endsWith(BasePublishTask.MAVEN_PUBLICATION_NAME) }
            .ifEmpty { publishing.publications.withType(MavenPublication::class.java).toList() }
            .map { PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version) }
        print(publications)
    }

    private fun mavenLocalRepository(): File = File(
        System.getProperty("maven.repo.local")
            ?: File(System.getProperty("user.home"), ".m2/repository").path
    ).canonicalFile
}
