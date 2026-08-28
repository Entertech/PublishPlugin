package custom.android.plugin

import org.gradle.api.publish.PublishingExtension


/** Legacy implementation; the public task name is now backed by PublishLocalTask. */
@Deprecated("Removed implementation; use PublishLocalTask registered by PublishPlugin")
internal open class PublishLibraryLocalTask : BasePublishTask() {

    companion object {
        const val TAG = "PublishLibraryLocalTask"
    }

    override fun initPublishCommandLine() = ":publishToMavenLocal"

    override fun getPublishingExtensionRepositoriesPath(publishing: PublishingExtension): String {
        return publishing.repositories.mavenLocal().url.toString()
    }

    override fun appendPublicationGroupPathToRepositoryPath(): Boolean = true

    override fun fetchTaskName(): String = TAG
}
