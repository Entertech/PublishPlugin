package custom.android.plugin

open class PublishInfo {


    companion object {
        const val EXTENSION_PUBLISH_INFO_NAME = "PublishInfo"
    }

    constructor()

    constructor(groupId: String, artifactId: String, version: String) {
        this.groupId = groupId
        this.artifactId = artifactId
        this.version = version
    }

    constructor(
        groupId: String,
        artifactId: String,
        version: String,
        pluginId: String,
        implementationClass: String
    ) {
        this.groupId = groupId
        this.artifactId = artifactId
        this.version = version
        this.pluginId = pluginId
        this.implementationClass = implementationClass
    }


    /**
     * 包名
     */
    var groupId = ""

    /**
     * 项目名
     */
    var artifactId = ""

    /**
     * 版本号
     */
    var version = ""

    var pluginId = ""

    var implementationClass = ""

    var publishUrl: String = "https://s01.oss.sonatype.org/content/repositories/releases/"

    var publishUserName: String = "REMOVED_ACCOUNT"
    var publishPassword: String = "REMOVED_PASSWORD"
}