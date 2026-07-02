package custom.android.plugin

import com.android.build.gradle.LibraryExtension
import custom.android.plugin.BasePublishTask.Companion.MAVEN_PUBLICATION_NAME
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.component.SoftwareComponent
import org.gradle.api.plugins.PluginContainer
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.jvm.tasks.Jar
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import java.net.URI
import java.util.Properties

/**
 * 执行publishToMavenLocal
 * */
open class PublishPlugin : Plugin<Project> {
    companion object {

        private const val TAG = "PublishPlugin"
    }

    private fun supportAppModule(container: PluginContainer): Boolean {
        return container.hasPlugin("com.android.application")
    }

    private fun supportPluginModule(container: PluginContainer): Boolean {
        return container.hasPlugin("org.gradle.kotlin.kotlin-dsl") || container.hasPlugin("groovy")
    }

    private fun supportLibraryModule(container: PluginContainer) =
        container.hasPlugin("com.android.library")


    override fun apply(project: Project) {
        // 应用Gradle官方的Maven插件

        val container = project.plugins
        if (supportAppModule(container)) {
            PluginLogUtil.printlnDebugInScreen("$TAG is app")
            return
        }
        container.apply(MavenPublishPlugin::class.java)
        project.extensions.create(
            PublishInfo.EXTENSION_PUBLISH_INFO_NAME, PublishInfo::class.java,
        )
        project.afterEvaluate { currentProject ->
            try {
                val publishInfo = project.extensions.getByType(PublishInfo::class.java)
                val publishing = project.extensions.getByType(PublishingExtension::class.java)
                val components = currentProject.components
                components.forEach {
                    PluginLogUtil.printlnDebugInScreen("$TAG name: ${it.name}")
                    if (supportPluginModule(container)) {
                        if (it.name == "java") {
                            val gradlePluginDevelopmentExtension =
                                project.extensions.getByType(GradlePluginDevelopmentExtension::class.java)
                            gradlePluginDevelopmentExtension.plugins { namedDomainObjectContainer ->
                                namedDomainObjectContainer.create("gradlePluginCreate") { pluginDeclaration ->
                                    // 插件ID
                                    pluginDeclaration.id = publishInfo.pluginId
                                    // 插件的实现类
                                    pluginDeclaration.implementationClass =
                                        publishInfo.implementationClass
                                }
                            }
                            publishing(project, publishing, publishInfo, it)
                        }
                    }
                    if (supportLibraryModule(container)) {
                        if (it.name == "release") {
                            //注册上传task
                            publishing(project, publishing, publishInfo, it)
                        }
                    }
                }

            } catch (e: Exception) {
                PluginLogUtil.printlnErrorInScreen("$TAG PluginModule error ${e.message}")
            }


        }
        val currProjectName = project.displayName
        PluginLogUtil.printlnDebugInScreen("$TAG currProjectName $currProjectName")
        project.gradle.afterProject { currProject ->
            PluginLogUtil.printlnDebugInScreen("$TAG currProject.displayName ${currProject.displayName}")
            if (currProjectName == currProject.displayName) {
                PluginLogUtil.printlnDebugInScreen("$TAG $currProjectName start register ")
                project.tasks.register(
                    PublishLibraryLocalTask.TAG, PublishLibraryLocalTask::class.java
                )
                project.tasks.register(
                    PublishLibraryRemoteTask.TAG, PublishLibraryRemoteTask::class.java
                )
            }
        }
    }

    private fun registerTask(container: TaskContainer, task: BasePublishTask) {
        container.register(
            task.fetchTaskName(), task::class.java
        )
    }

    private fun publishing(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo,
        softwareComponent: SoftwareComponent
    ) {
        val publishSources = publishInfo.version.endsWith("-debug")
        skipSourcesVariants(project, softwareComponent)
        publishing.publications { publications ->
            publications.create(
                MAVEN_PUBLICATION_NAME, MavenPublication::class.java
            ) { publication ->
                publication.groupId = publishInfo.groupId
                publication.artifactId = publishInfo.artifactId
                publication.version = publishInfo.version
                publication.from(softwareComponent)
                if (publishSources) {
                    createSourcesJarTask(project)?.let { task ->
                        publication.artifact(task)
                    }
                }
                if (!publishSources) {
                    removeSourcesArtifacts(publication)
                }
            }
        }

        val properties = Properties()// local.properties file in the root director
        properties.load(project.rootProject.file("local.properties").inputStream())
        PluginLogUtil.printlnDebugInScreen("properties: $properties")
        var publishUrl = publishInfo.publishUrl
        if (publishUrl.isEmpty()) {
            publishUrl = properties.getProperty("publishUrl", "")
        }
        var publishUserName = publishInfo.publishUserName
        if (publishUserName.isEmpty()) {
            publishUserName = properties.getProperty("publishUserName", "")
        }
        var publishPassword = publishInfo.publishPassword
        if (publishPassword.isEmpty()) {
            publishPassword = properties.getProperty("publishPassword", "")
        }
        PluginLogUtil.printlnDebugInScreen("$TAG publishUrl is $publishUrl")
        PluginLogUtil.printlnDebugInScreen("$TAG publishUserName is $publishUserName  publishPassword is $publishPassword")
        if (publishUrl.isEmpty()) {
            publishUrl = "https://s01.oss.sonatype.org/content/repositories/releases/"
        }
        if (publishUserName.isEmpty()) {
            publishUserName = "REMOVED_ACCOUNT"
        }
        if (publishPassword.isEmpty()) {
            publishPassword = "REMOVED_PASSWORD"
        }
        if (publishUrl.isNotEmpty()) {
            publishing.repositories { artifactRepositories ->
                artifactRepositories.maven { mavenArtifactRepository ->
                    mavenArtifactRepository.url = URI(publishUrl)
                    mavenArtifactRepository.isAllowInsecureProtocol = true
                    mavenArtifactRepository.credentials { credentials ->
                        credentials.username = publishUserName
                        credentials.password = publishPassword
                    }
                }
            }
        }
    }

    private fun skipSourcesVariants(project: Project, softwareComponent: SoftwareComponent) {
        val componentWithVariants = softwareComponent as? AdhocComponentWithVariants ?: return
        listOf("${softwareComponent.name}SourcesElements", "sourcesElements")
            .distinct()
            .mapNotNull { project.configurations.findByName(it) }
            .forEach { configuration ->
                componentWithVariants.withVariantsFromConfiguration(configuration) { details ->
                    details.skip()
                }
            }
    }

    private fun createSourcesJarTask(project: Project): Any? {
        val androidSet = project.extensions.findByName("android") as? LibraryExtension
        if (androidSet != null) {
            val sourceSetFiles = androidSet.sourceSets.findByName("main")?.java?.srcDirs ?: return null
            return createSourcesJarTask(project, "androidSourcesJar", sourceSetFiles)
        }

        val sourceSets = project.extensions.findByName("sourceSets") as? SourceSetContainer ?: return null
        val mainSourceSet = sourceSets.findByName("main") ?: return null
        return createSourcesJarTask(project, "sourcesJar", mainSourceSet.allSource)
    }

    private fun createSourcesJarTask(project: Project, taskName: String, source: Any): Any {
        return project.tasks.findByName(taskName) ?: project.tasks.create(
            taskName, Jar::class.java
        ) { jar ->
            jar.from(source)
            jar.archiveClassifier.set("sources")
        }
    }

    private fun removeSourcesArtifacts(publication: MavenPublication) {
        publication.artifacts.toList()
            .filter { it.classifier == "sources" }
            .forEach { publication.artifacts.remove(it) }
    }

}
