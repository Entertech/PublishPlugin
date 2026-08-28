package custom.android.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CentralPortalBundle {
    fun create(bundle: PreparedArtifactBundle, output: File): File {
        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            bundle.publications.forEach { publication ->
                publication.files.forEach { file ->
                    val source = File(bundle.rootDirectory, file.path)
                    val entryName = "${publication.groupId.replace('.', '/')}/${publication.artifactId}/${publication.version}/${source.name}"
                    zip.putNextEntry(ZipEntry(entryName))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return output
    }
}
