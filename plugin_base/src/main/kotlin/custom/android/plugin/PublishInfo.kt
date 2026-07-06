package custom.android.plugin

import groovy.lang.Closure
import java.time.Year

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

    var centralNamespace: String = "cn.entertech"
    var centralPublishingType: String = "user_managed"
    var centralRepositoryName: String = "CentralStaging"

    var pomName: String = ""
    var pomDescription: String = ""
    var pomInceptionYear: String = Year.now().value.toString()
    var pomUrl: String = ""

    var licenseName: String = "The Apache License, Version 2.0"
    var licenseUrl: String = "https://www.apache.org/licenses/LICENSE-2.0.txt"
    var licenseDistribution: String = "repo"

    var developerId: String = "Entertech"
    var developerName: String = "Entertech"
    var developerEmail: String = "developer@entertech.cn"
    var developerOrganization: String = "Entertech"
    var developerOrganizationUrl: String = "https://github.com/Entertech"
    var developerUrl: String = "https://github.com/Entertech"

    var scmUrl: String = ""
    var scmConnection: String = ""
    var scmDeveloperConnection: String = ""

    private var artifactIdForVariantAction: ((PublishVariantInfo) -> String)? = null
    private val skipVariantActions = mutableListOf<(PublishVariantInfo) -> Boolean>()

    fun artifactIdForVariant(action: (PublishVariantInfo) -> String) {
        artifactIdForVariantAction = action
    }

    fun artifactIdForVariant(action: Closure<*>) {
        artifactIdForVariantAction = { variant ->
            action.call(variant)?.toString().orEmpty()
        }
    }

    fun skipVariantIf(action: (PublishVariantInfo) -> Boolean) {
        skipVariantActions += action
    }

    fun skipVariantIf(action: Closure<*>) {
        skipVariantActions += { variant ->
            action.call(variant) == true
        }
    }

    internal fun resolveArtifactId(variant: PublishVariantInfo?): String {
        val action = artifactIdForVariantAction
        if (variant == null || action == null) {
            return artifactId
        }
        return action(variant).ifBlank { artifactId }
    }

    internal fun shouldPublishVariant(variant: PublishVariantInfo): Boolean {
        return skipVariantActions.none { action -> action(variant) }
    }
}

open class PublishVariantInfo(
    val name: String,
    val buildType: String,
    val flavors: Map<String, String>
) {
    fun flavor(dimension: String): String {
        return flavors[dimension].orEmpty()
    }
}
