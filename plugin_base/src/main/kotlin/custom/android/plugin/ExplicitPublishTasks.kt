package custom.android.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExplicitPublishTarget {
    LOCAL,
    GITHUB_PACKAGES,
    CENTRAL,
    ALL
}

open class ExplicitPublishTask : DefaultTask() {
    @get:Input
    var target: ExplicitPublishTarget = ExplicitPublishTarget.LOCAL

    @get:Input
    var componentKind: PublishComponentKind = PublishComponentKind.LIBRARY

    private val providerResults = mutableListOf<PublishProviderResult>()

    init {
        group = "customPlugin"
    }

    @TaskAction
    fun publish() {
        val source = ArtifactSource.parse(
            project.findProperty("artifactSource")?.toString()
                ?: System.getenv("PUBLISH_ARTIFACT_SOURCE")
        )
        val publishInfo = project.extensions.findByType(PublishInfo::class.java)
            ?: throw GradleException("PublishInfo is required for ${componentKind.taskNamePart} publishing")
        val version = PublishConfigResolver.resolveVersion(project, publishInfo)
        var validation = PublishValidation.validateRemote(project, publishInfo, target, source)
        validation.warnings.forEach { PluginLogUtil.printlnInfoInScreen("WARNING: $it") }
        if (!validation.valid) {
            validation.errors.forEach { PluginLogUtil.printlnErrorInScreen(it) }
            throw GradleException("发布配置校验失败")
        }
        var bundle: PreparedArtifactBundle? = null
        try {
            bundle = when {
                source == ArtifactSource.PREBUILT -> preparePrebuilt(publishInfo, version)
                shouldPrepareProjectBundle() -> ProjectArtifactBundleProducer.prepare(
                    project,
                    validation.publications,
                    requireCentral = target == ExplicitPublishTarget.CENTRAL ||
                        (target == ExplicitPublishTarget.ALL &&
                            project.extensions.findByType(PublishRepositories::class.java)?.isCentralEnabled() == true)
                )
                else -> null
            }
            if (bundle != null) {
                validation = validation.copy(publications = bundle.publications.map {
                    PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version)
                })
            }
            val preflight = PublishPreflight.run(project, publishInfo, target, validation.publications)
            validation = validation.copy(preflightResults = preflight)
            preflight.filter { it.status == "failed" }.takeIf { it.isNotEmpty() }?.let { failures ->
                throw GradleException("Publish preflight failed: ${failures.joinToString { "${it.provider}: ${it.message}" }}")
            }
            val supplyChain = PublishSupplyChain.collect(project, bundle)
            validation = validation.copy(gates = supplyChain.gates, provenance = supplyChain.provenance)
            supplyChain.gates.filter { it.status == "failed" }.takeIf { it.isNotEmpty() }?.let { failures ->
                throw GradleException("Publish gate failed: ${failures.joinToString { "${it.name}: ${it.message}" }}")
            }
            bundle = if (source == ArtifactSource.PREBUILT) {
                publishPrebuilt(requireNotNull(bundle), publishInfo)
            } else {
                publishProject(publishInfo, bundle)
            }
        } catch (error: Exception) {
            PublishReport.write(project, validation.copy(providerResults = providerResults.toList()), dryRun = false)
            throw error
        }
        if (bundle != null) {
            validation = validation.copy(publications = bundle.publications.map {
                PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version)
            })
        }
        PublishReport.write(project, validation.copy(providerResults = providerResults.toList()), dryRun = false)
        if (source == ArtifactSource.PROJECT &&
            project.findProperty("cleanupPreparedBundle")?.toString().toBoolean() &&
            bundle?.rootDirectory?.name == "project-bundle"
        ) {
            bundle.rootDirectory.deleteRecursively()
        }
    }

    private fun preparePrebuilt(publishInfo: PublishInfo, version: String): PreparedArtifactBundle {
        val centralEnabled = project.extensions.findByType(PublishRepositories::class.java)
            ?.isCentralEnabled() == true
        val requireCentral = target == ExplicitPublishTarget.CENTRAL ||
            (target == ExplicitPublishTarget.ALL && centralEnabled)
        val path = project.findProperty("artifactBundlePath")?.toString()
            ?: System.getenv("PUBLISH_ARTIFACT_BUNDLE_PATH").orEmpty()
        val bundle = PrebuiltArtifactBundleProducer.prepare(project, path, version, requireCentral)
        if (requireCentral) validateCentralBundleNamespace(bundle, publishInfo)
        return bundle
    }

    private fun publishPrebuilt(bundle: PreparedArtifactBundle, publishInfo: PublishInfo): PreparedArtifactBundle {
        when (target) {
            ExplicitPublishTarget.LOCAL -> ArtifactBundlePublisher.publishToMavenLocal(project, bundle)
            ExplicitPublishTarget.GITHUB_PACKAGES -> publishPrebuiltGithub(bundle, publishInfo)
            ExplicitPublishTarget.CENTRAL -> publishPrebuiltCentral(bundle, publishInfo)
            ExplicitPublishTarget.ALL -> publishAll(bundle, publishInfo, projectSource = false)
        }
        return bundle
    }

    private fun validateCentralBundleNamespace(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
        bundle.publications.forEach { publication ->
            if (publication.groupId != namespace && !publication.groupId.startsWith("$namespace.")) {
                throw GradleException(
                    "Prebuilt publication ${publication.groupId}:${publication.artifactId} must be under centralNamespace($namespace)"
                )
            }
        }
    }

    private fun publishPrebuiltGithub(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val config = PublishRuntimeConfig(project)
        val repository = PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, config.properties)
        val credentials = PublishConfigResolver.resolveGitHubPackagesCredentials(project, publishInfo, config.properties)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            throw GradleException("GitHub Packages prebuilt publishing requires GitHub Packages credentials")
        }
        ArtifactBundlePublisher.publishToRemote(project, bundle, repository, credentials.username, credentials.password)
    }

    private fun publishPrebuiltCentral(bundle: PreparedArtifactBundle, publishInfo: PublishInfo) {
        val config = PublishRuntimeConfig(project)
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo, config.properties)
        if (credentials.username.isBlank() || credentials.password.isBlank()) {
            throw GradleException("Central prebuilt publishing requires Central credentials")
        }
        if (PublishConfigResolver.resolveCentralUploadMode(project, publishInfo) == "portalApi") {
            val zip = CentralPortalBundle.create(bundle, File(project.buildDir, "reports/publish/central-bundle.zip"))
            val deploymentId = CentralPortalClient.uploadBundle(project, zip, publishInfo)
            CentralPortalClient.waitForDeployment(project, publishInfo, deploymentId)
            if (PublishConfigResolver.resolveCentralPublishingType(project, publishInfo) == "automatic") {
                CentralPortalClient.publishDeployment(project, publishInfo, deploymentId)
            }
        } else {
            ArtifactBundlePublisher.publishToRemote(
                project,
                bundle,
                PublishConfigResolver.resolveCentralRepositoryUrl(project, publishInfo),
                credentials.username,
                credentials.password
            )
            finalizeCentralPublication(publishInfo)
        }
    }

    private fun publishProject(
        publishInfo: PublishInfo,
        preparedBundle: PreparedArtifactBundle?
    ): PreparedArtifactBundle? {
        fun targetProperties(): Map<String, String> =
            project.gradle.startParameter.projectProperties
                .filterValues { it.isNotBlank() }
                .toMap()
        return when (target) {
            ExplicitPublishTarget.LOCAL -> runNested("publishToMavenLocal", targetProperties())
                .let { null }
            ExplicitPublishTarget.GITHUB_PACKAGES -> requireNotNull(preparedBundle) {
                "Project remote bundle was not prepared"
            }.also { publishPrebuiltGithub(it, publishInfo) }
            ExplicitPublishTarget.CENTRAL -> {
                requireNotNull(preparedBundle) { "Project remote bundle was not prepared" }
                    .also { publishPrebuiltCentral(it, publishInfo) }
            }
            ExplicitPublishTarget.ALL -> requireNotNull(preparedBundle) {
                "Project remote bundle was not prepared"
            }.also { publishAll(it, publishInfo, projectSource = true) }
        }
    }

    private fun shouldPrepareProjectBundle(): Boolean {
        return target != ExplicitPublishTarget.LOCAL
    }

    private fun publishAll(bundle: PreparedArtifactBundle, publishInfo: PublishInfo, projectSource: Boolean) {
        val repositories = project.extensions.getByType(PublishRepositories::class.java)
        val store = PublishExecutionStateStore(project)
        val states = store.read().toMutableMap()
        val fingerprint = PublishExecutionStateStore.fingerprint(
            bundle.publications.map { PublishValidationPublication(it.name, it.groupId, it.artifactId, it.version) },
            bundle
        )
        val resume = project.findProperty("resumePublish")?.toString().toBoolean()
        val enabled = buildList {
            if (repositories.isGithubPackagesEnabled()) add("github_packages")
            if (repositories.isCentralEnabled()) add("central")
        }.let { providers ->
            val requested = project.findProperty("publishProviderOrder")?.toString().orEmpty()
                .split(',').map { it.trim().lowercase() }.filter { it in providers }
            (requested + providers).distinct()
        }
        enabled.forEach { provider ->
            states.putIfAbsent(provider, PublishProviderState(provider, "not_started", fingerprint))
        }
        store.write(states)
        enabled.forEach { provider ->
            val previous = states[provider]
            if (resume && previous?.status in setOf("succeeded", "skipped") && previous?.fingerprint == fingerprint) {
                states[provider] = PublishProviderState(provider, "skipped", fingerprint, "already succeeded for identical bundle")
                store.write(states)
                providerResults += PublishProviderResult(provider, "skipped", "already succeeded for identical bundle")
                return@forEach
            }
            states[provider] = PublishProviderState(provider, "running", fingerprint)
            store.write(states)
            try {
                when (provider) {
                    "github_packages" -> publishPrebuiltGithub(bundle, publishInfo)
                    "central" -> publishPrebuiltCentral(bundle, publishInfo)
                }
                states[provider] = PublishProviderState(provider, "succeeded", fingerprint)
                store.write(states)
                providerResults += PublishProviderResult(provider, "succeeded")
            } catch (error: Exception) {
                val safeMessage = sanitizeProviderMessage(error.message.orEmpty())
                states[provider] = PublishProviderState(provider, "failed", fingerprint, safeMessage)
                store.write(states)
                providerResults += PublishProviderResult(provider, "failed", safeMessage)
                val sourceName = if (projectSource) "project" else "prebuilt"
                throw GradleException(
                    "$provider $sourceName publishing failed; rerun with -PresumePublish=true to continue: ${error.message}",
                    error
                )
            }
        }
    }

    private fun sanitizeProviderMessage(value: String): String = value
        .replace(Regex("(?i)(password|token|secret|authorization|key)[^\\s,}]*"), "$1=***")
        .replace('"', '\'')
        .take(500)

    private fun finalizeCentralPublication(publishInfo: PublishInfo) {
        if (!PublishConfigResolver.isCentralSnapshotPublish(project, publishInfo)) {
            CentralPortalClient.manualUpload(project, publishInfo)
        }
    }

    private fun runNested(taskName: String, properties: Map<String, String>) {
        val output = ByteArrayOutputStream()
        val executable = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "gradlew.bat" else "gradlew"
        val path = File(project.rootProject.projectDir, executable)
        if (!path.exists()) throw GradleException("Gradle wrapper not found: ${path.path}")
        val realTask = project.path + ":" + taskName
        val result = project.exec { exec: ExecSpec ->
            exec.standardOutput = output
            exec.errorOutput = output
            exec.isIgnoreExitValue = true
            properties.forEach { (key, value) -> exec.environment("ORG_GRADLE_PROJECT_$key", value) }
            exec.commandLine(path.absolutePath)
            System.getProperty("maven.repo.local")?.takeIf { it.isNotBlank() }?.let {
                exec.args("-Dmaven.repo.local=$it")
            }
            exec.args(realTask, "--no-daemon", "--stacktrace")
        }
        if (result.exitValue != 0) {
            throw GradleException("Publish task failed ($realTask): ${output.toString().takeLast(4000)}")
        }
    }
}

open class PublishLocalTask : ExplicitPublishTask()
open class PublishRemoteGithubPackagesTask : ExplicitPublishTask()
open class PublishRemoteCentralTask : ExplicitPublishTask()
open class PublishRemoteAllTask : ExplicitPublishTask()
