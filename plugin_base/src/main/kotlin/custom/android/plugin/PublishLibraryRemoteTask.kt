package custom.android.plugin

import org.gradle.api.publish.PublishingExtension


open class PublishLibraryRemoteTask : BasePublishTask() {

    companion object {
        const val TAG = "PublishLibraryRemoteTask"
    }


    override fun initPublishCommandLine() =
        ":publish${MAVEN_PUBLICATION_NAME}PublicationToMavenRepository"


    override fun checkPublishInfo(publishInfo: PublishInfo): Boolean {
        val version = publishInfo.version
        if(version.endsWith("debug")){
            PluginLogUtil.printlnErrorInScreen("$publishInfo version end with debug")
            return false
        }
        return true
    }

    override fun getPublishingExtensionRepositoriesPath(publishing: PublishingExtension): String {
        return publishing.repositories.mavenCentral().url.toString()
    }

    override fun fetchTaskName(): String = TAG
}