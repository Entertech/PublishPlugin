package custom.android.plugin

import custom.android.plugin.config.CentralPublishConfigTemplateWriter
import custom.android.plugin.config.GitSafetyChecker
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

open class GenerateCentralPublishConfigTask : DefaultTask() {
    init {
        group = "customPlugin"
        description = "Generate or update local.properties centralPublish.* template."
    }

    @TaskAction
    fun generate() {
        val configFile = PublishConfigResolver.centralPublishConfigFile(project)
        if (GitSafetyChecker.isTracked(project.rootDir, configFile)) {
            throw GradleException("${configFile.name} is tracked by git. Run: git rm --cached ${configFile.name}")
        }
        CentralPublishConfigTemplateWriter.writeTemplate(
            project.rootDir,
            configFile,
            project.findProperty("overwritePublishConfig")?.toString()?.toBooleanLenientLocal() == true
        )
        PluginLogUtil.printlnInfoInScreen("Generated central publish config: ${configFile.absolutePath}")
    }
}
