package custom.android.plugin

import custom.android.plugin.config.PublishConfigTemplateWriter
import custom.android.plugin.config.GitSafetyChecker
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction

open class GeneratePublishConfigTask : DefaultTask() {
    init {
        group = "customPlugin"
        description = "Generate or update local.properties publish configuration template."
    }

    @TaskAction
    fun generate() {
        val configFile = PublishConfigResolver.publishConfigFile(project)
        if (GitSafetyChecker.isTracked(project.rootDir, configFile)) {
            throw GradleException("${configFile.name} is tracked by git. Run: git rm --cached ${configFile.name}")
        }
        PublishConfigTemplateWriter.writeTemplate(
            project.rootDir,
            configFile,
            project.findProperty("overwritePublishConfig")?.toString()?.toBooleanLenientLocal() == true
        )
        PluginLogUtil.printlnInfoInScreen("Generated publish config: ${configFile.absolutePath}")
    }
}
