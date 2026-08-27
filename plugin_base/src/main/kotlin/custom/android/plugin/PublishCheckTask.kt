package custom.android.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

open class PublishCheckTask : DefaultTask() {
    @get:Input
    var target: ExplicitPublishTarget = ExplicitPublishTarget.LOCAL

    @TaskAction
    fun check() {
        val publishInfo = project.extensions.findByType(PublishInfo::class.java)
            ?: throw GradleException("PublishInfo is required for publish checks")
        val resolvedTarget = resolveTarget()
        val source = ArtifactSource.parse(
            project.findProperty("artifactSource")?.toString() ?: System.getenv("PUBLISH_ARTIFACT_SOURCE")
        )
        var result = PublishValidation.validateRemote(project, publishInfo, resolvedTarget, source)
        if (source == ArtifactSource.PREBUILT) {
            val path = project.findProperty("artifactBundlePath")?.toString()
                ?: System.getenv("PUBLISH_ARTIFACT_BUNDLE_PATH").orEmpty()
            val bundle = PrebuiltArtifactBundleProducer.prepare(
                project,
                path,
                PublishConfigResolver.resolveVersion(project, publishInfo),
                resolvedTarget == ExplicitPublishTarget.CENTRAL || resolvedTarget == ExplicitPublishTarget.ALL
            )
            result = result.copy(
                publications = bundle.publications.map {
                    PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version)
                }
            )
        }
        PluginLogUtil.printlnInfoInScreen("Publish check: mode=${result.mode}, repository=${result.repositoryName}")
        if (result.repositoryUrl.isNotBlank()) PluginLogUtil.printlnInfoInScreen("Repository URL: ${result.repositoryUrl}")
        result.publications.forEach {
            PluginLogUtil.printlnInfoInScreen("Publication: ${it.name} ${it.groupId}:${it.artifactId}:${it.version}")
        }
        result.warnings.forEach { PluginLogUtil.printlnInfoInScreen("WARNING: $it") }
        if (!result.valid) {
            result.errors.forEach { PluginLogUtil.printlnErrorInScreen(it) }
            throw GradleException("发布配置校验失败")
        }
        PublishReport.write(project, result, dryRun = true)
        PluginLogUtil.printlnInfoInScreen("Publish configuration is valid; no artifacts were uploaded.")
    }

    private fun resolveTarget(): ExplicitPublishTarget {
        val requested = project.findProperty("checkPublishTarget")?.toString()?.trim()?.lowercase()
            ?: project.findProperty("publishTarget")?.toString()?.trim()?.lowercase()
            ?: System.getenv("PUBLISH_TARGET")?.trim()?.lowercase()
        return when (requested) {
            "github_packages", "githubpackages" -> ExplicitPublishTarget.GITHUB_PACKAGES
            "central", "centralsnapshot" -> ExplicitPublishTarget.CENTRAL
            "all" -> ExplicitPublishTarget.ALL
            "customrepository" -> ExplicitPublishTarget.GITHUB_PACKAGES
            else -> target
        }
    }
}
