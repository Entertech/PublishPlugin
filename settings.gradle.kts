pluginManagement {
    includeBuild("plugin_base")

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

// Plugin-only CI does not need to configure the consumer examples. The plugin
// itself remains available as the included build at :plugin_base.
val pluginBaseOnly = providers.gradleProperty("pluginBaseOnly").orNull?.toBoolean() ?: false
if (!pluginBaseOnly) {
    include(":app")
    include(":demo-lib")
    include(":demo-plugin")
}
