pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "EnterTechPlugin"
include(":plugin_base")

// Plugin-only CI must not configure demos that consume APIs from the plugin
// version currently being built but not published yet.
val pluginBaseOnly = providers.gradleProperty("pluginBaseOnly").orNull?.toBoolean() ?: false
if (!pluginBaseOnly) {
    include(":app")
    include(":demo-lib")
    include(":demo-plugin")
}
