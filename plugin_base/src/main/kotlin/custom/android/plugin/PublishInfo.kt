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
        set(value) {
            markExplicit("groupId")
            field = value
        }

    /**
     * 项目名
     */
    var artifactId = ""
        set(value) {
            markExplicit("artifactId")
            field = value
        }

    /**
     * 版本号
     */
    var version = ""
        set(value) {
            markExplicit("version")
            field = value
        }

    var pluginId = ""
        set(value) {
            markExplicit("pluginId")
            field = value
        }

    var implementationClass = ""
        set(value) {
            markExplicit("implementationClass")
            field = value
        }

    var publishUrl: String = ""

    var publishUserName: String = ""
    var publishPassword: String = ""

    var remotePublishMode: String = "central"

    var githubPackagesRepository: String = ""
    var githubPackagesUrl: String = ""
    var githubPackagesRepositoryName: String = "GitHubPackages"
    var githubPackagesUsername: String = ""
    var githubPackagesPassword: String = ""

    var centralNamespace: String = "cn.entertech"
        set(value) {
            markExplicit("centralNamespace")
            field = value
        }
    var centralPublishingType: String = "user_managed"
        set(value) {
            markExplicit("centralPublishingType")
            field = value
        }
    var centralRepositoryName: String = "CentralStaging"
        set(value) {
            markExplicit("centralRepositoryName")
            field = value
        }

    var pomName: String = ""
        set(value) {
            markExplicit("pomName")
            field = value
        }
    var pomDescription: String = ""
        set(value) {
            markExplicit("pomDescription")
            field = value
        }
    var pomInceptionYear: String = Year.now().value.toString()
        set(value) {
            markExplicit("pomInceptionYear")
            field = value
        }
    var pomUrl: String = ""
        set(value) {
            markExplicit("pomUrl")
            field = value
        }

    var licenseName: String = "The Apache License, Version 2.0"
        set(value) {
            markExplicit("licenseName")
            field = value
        }
    var licenseUrl: String = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        set(value) {
            markExplicit("licenseUrl")
            field = value
        }
    var licenseDistribution: String = "repo"
        set(value) {
            markExplicit("licenseDistribution")
            field = value
        }

    var developerId: String = "Entertech"
        set(value) {
            markExplicit("developerId")
            field = value
        }
    var developerName: String = "Entertech"
        set(value) {
            markExplicit("developerName")
            field = value
        }
    var developerEmail: String = "developer@entertech.cn"
        set(value) {
            markExplicit("developerEmail")
            field = value
        }
    var developerOrganization: String = "Entertech"
        set(value) {
            markExplicit("developerOrganization")
            field = value
        }
    var developerOrganizationUrl: String = "https://github.com/Entertech"
        set(value) {
            markExplicit("developerOrganizationUrl")
            field = value
        }
    var developerUrl: String = "https://github.com/Entertech"
        set(value) {
            markExplicit("developerUrl")
            field = value
        }

    var scmUrl: String = ""
        set(value) {
            markExplicit("scmUrl")
            field = value
        }
    var scmConnection: String = ""
        set(value) {
            markExplicit("scmConnection")
            field = value
        }
    var scmDeveloperConnection: String = ""
        set(value) {
            markExplicit("scmDeveloperConnection")
            field = value
        }

    private var artifactIdForVariantAction: ((PublishVariantInfo) -> String)? = null
    private val skipVariantActions = mutableListOf<(PublishVariantInfo) -> Boolean>()
    private val explicitFields = mutableSetOf<String>()

    internal fun isExplicit(fieldName: String): Boolean {
        return fieldName in explicitFields
    }

    private fun markExplicit(fieldName: String) {
        explicitFields += fieldName
    }

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
