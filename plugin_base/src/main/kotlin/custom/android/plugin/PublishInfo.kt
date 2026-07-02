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

    var publishUrl: String = ""

    var publishUserName: String = ""
    var publishPassword: String = ""

    var remotePublishMode: String = "central"

    var centralNamespace: String = ""
    var centralPublishingType: String = "user_managed"
    var centralRepositoryName: String = "CentralStaging"

    var pomName: String = ""
    var pomDescription: String = ""
    var pomInceptionYear: String = ""
    var pomUrl: String = ""

    var licenseName: String = "The Apache License, Version 2.0"
    var licenseUrl: String = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    var licenseDistribution: String = "repo"

    var developerId: String = ""
    var developerName: String = ""
    var developerEmail: String = ""
    var developerOrganization: String = ""
    var developerOrganizationUrl: String = ""
    var developerUrl: String = ""

    var scmUrl: String = ""
    var scmConnection: String = ""
    var scmDeveloperConnection: String = ""
}
