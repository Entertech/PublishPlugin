package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.plugins.PluginContainer

enum class PublishComponentKind(val taskNamePart: String) {
    LIBRARY("Library"),
    PLUGIN("Plugin");

    companion object {
        fun detect(plugins: PluginContainer): PublishComponentKind {
            val plugin = plugins.hasPlugin("java-gradle-plugin") ||
                plugins.hasPlugin("org.gradle.kotlin.kotlin-dsl")
            val library = plugins.hasPlugin("com.android.library")
            if (plugin && library) {
                throw GradleException(
                    "PublishPlugin cannot infer a unique component type: both Library and Plugin plugins are applied."
                )
            }
            return when {
                library -> LIBRARY
                plugin || plugins.hasPlugin("groovy") -> PLUGIN
                else -> throw GradleException(
                    "PublishPlugin requires com.android.library or java-gradle-plugin to register publish tasks."
                )
            }
        }
    }
}
