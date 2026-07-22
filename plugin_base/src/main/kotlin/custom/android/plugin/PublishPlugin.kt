package custom.android.plugin

import com.android.build.gradle.LibraryExtension
import custom.android.plugin.BasePublishTask.Companion.MAVEN_PUBLICATION_NAME
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
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
import org.gradle.plugins.signing.SigningExtension
import java.net.URI
import java.io.File

/**
 * 执行publishToMavenLocal
 * */
open class PublishPlugin : Plugin<Project> {
    companion object {

        private const val TAG = "PublishPlugin"
    }

    private data class PublishTarget(
        val component: SoftwareComponent,
        val publicationName: String,
        val groupId: String,
        val artifactId: String,
        val version: String
    )

    private data class AndroidFlavorSpec(
        val name: String,
        val dimension: String
    )

    private fun supportAppModule(container: PluginContainer): Boolean {
        return container.hasPlugin("com.android.application")
    }

    private fun supportPluginModule(container: PluginContainer): Boolean {
        return container.hasPlugin("java-gradle-plugin") ||
            container.hasPlugin("org.gradle.kotlin.kotlin-dsl") ||
            container.hasPlugin("groovy")
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
        project.plugins.withId("com.android.library") {
            configureAndroidSingleVariantPublishing(project)
        }
        project.afterEvaluate { currentProject ->
            try {
                val publishInfo = project.extensions.getByType(PublishInfo::class.java)
                val publishing = project.extensions.getByType(PublishingExtension::class.java)
                val components = currentProject.components.map { it }
                var hasPublication = false
                if (supportPluginModule(container)) {
                    components.forEach {
                    PluginLogUtil.printlnDebugInScreen("$TAG name: ${it.name}")
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
                            createPublication(
                                project,
                                publishing,
                                publishInfo,
                                it,
                                MAVEN_PUBLICATION_NAME,
                                publishInfo.groupId,
                                publishInfo.artifactId,
                                publishInfo.version
                            )
                            hasPublication = true
                        }
                    }
                }
                if (supportLibraryModule(container)) {
                    val publishTargets = resolveAndroidPublishTargets(project, components, publishInfo)
                    publishTargets.forEach {
                        PluginLogUtil.printlnDebugInScreen(
                            "$TAG publish ${it.component.name} as ${it.publicationName}:${it.artifactId}"
                        )
                        createPublication(
                            project,
                            publishing,
                            publishInfo,
                            it.component,
                            it.publicationName,
                            it.groupId,
                            it.artifactId,
                            it.version
                        )
                    }
                    hasPublication = hasPublication || publishTargets.isNotEmpty()
                }
                if (hasPublication) {
                    configurePublishingRepositories(project, publishing, publishInfo)
                }

            } catch (e: Exception) {
                PluginLogUtil.printlnErrorInScreen("$TAG PluginModule error ${e.message}")
                throw e
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
                project.tasks.register(
                    "generatePublishConfig", GeneratePublishConfigTask::class.java
                )
                project.tasks.register(
                    "GeneratePublishConfigTask", GeneratePublishConfigTask::class.java
                )
                project.tasks.register(
                    "generateCentralPublishConfig", GeneratePublishConfigTask::class.java
                )
                project.tasks.register(
                    "GenerateCentralPublishConfigTask", GeneratePublishConfigTask::class.java
                )
                project.tasks.register(
                    "configurePublish", ConfigurePublishTask::class.java
                )
                project.tasks.register(
                    "ConfigurePublishTask", ConfigurePublishTask::class.java
                )
                project.tasks.register(
                    "configureCentralPublish", ConfigurePublishTask::class.java
                )
                project.tasks.register(
                    "ConfigureCentralPublishTask", ConfigurePublishTask::class.java
                )
                project.tasks.register(
                    "rollbackPublishSecrets", RollbackPublishSecretsTask::class.java
                )
                project.tasks.register(
                    "RollbackPublishSecretsTask", RollbackPublishSecretsTask::class.java
                )
                project.tasks.register(
                    "rollbackCentralPublishSecrets", RollbackPublishSecretsTask::class.java
                )
                project.tasks.register(
                    "RollbackCentralPublishSecretsTask", RollbackPublishSecretsTask::class.java
                )
            }
        }
    }

    private fun registerTask(container: TaskContainer, task: BasePublishTask) {
        container.register(
            task.fetchTaskName(), task::class.java
        )
    }

    private fun createPublication(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo,
        softwareComponent: SoftwareComponent,
        publicationName: String,
        groupId: String,
        artifactId: String,
        version: String
    ) {
        val centralPublish = PublishConfigResolver.isCentralPublish(project, publishInfo)
        val resolvedVersion = PublishConfigResolver.resolveVersion(project, publishInfo, version)
        val publishSources = centralPublish || resolvedVersion.endsWith("-debug")
        val publicationVersion = resolvePublicationVersion(project, resolvedVersion)
        skipSourcesVariants(project, softwareComponent)
        publishing.publications { publications ->
            publications.create(
                publicationName, MavenPublication::class.java
            ) { publication ->
                publication.groupId = groupId
                publication.artifactId = artifactId
                publication.version = publicationVersion
                publication.from(softwareComponent)
                configurePom(project, publication, publishInfo, artifactId)
                if (publishSources) {
                    createSourcesJarTask(project)?.let { task ->
                        publication.artifact(task)
                    }
                }
                if (centralPublish) {
                    publication.artifact(createJavadocJarTask(project))
                    configureSigning(project, publication)
                }
                if (!publishSources) {
                    removeSourcesArtifacts(publication)
                }
            }
        }
    }

    private fun resolvePublicationVersion(project: Project, version: String): String {
        if (!isLocalPublishRequested(project) || version.endsWith("-local")) {
            return version
        }
        return "$version-local"
    }

    private fun isLocalPublishRequested(project: Project): Boolean {
        return project.gradle.startParameter.taskNames.any { taskName ->
            val shortTaskName = taskName.substringAfterLast(":")
            shortTaskName == PublishLibraryLocalTask.TAG ||
                shortTaskName == "publishToMavenLocal" ||
                (shortTaskName.startsWith("publish") && shortTaskName.endsWith("PublicationToMavenLocal"))
        }
    }

    private fun configurePublishingRepositories(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo
    ) {
        val properties = PublishConfigResolver.loadLocalProperties(project)
        val mode = PublishConfigResolver.resolveRemotePublishMode(project, publishInfo)
        if (mode == PublishConfigResolver.MODE_CENTRAL) {
            configureCentralRepository(project, publishing, publishInfo, properties)
        } else if (mode == PublishConfigResolver.MODE_CUSTOM_REPOSITORY) {
            configureCustomRepository(project, publishing, publishInfo, properties)
        } else if (mode == PublishConfigResolver.MODE_GITHUB_PACKAGES) {
            configureGitHubPackagesRepository(project, publishing, publishInfo, properties)
        }
    }

    private fun configureAndroidSingleVariantPublishing(project: Project) {
        val androidComponents = project.extensions.findByName("androidComponents") ?: return
        val finalizeDslMethod = androidComponents.javaClass.methods.firstOrNull { method ->
            method.name == "finalizeDsl" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].isAssignableFrom(Action::class.java)
        }
        if (finalizeDslMethod == null) {
            PluginLogUtil.printlnDebugInScreen("$TAG androidComponents.finalizeDsl is unavailable")
            return
        }

        finalizeDslMethod.invoke(androidComponents, Action<Any> { androidDsl ->
            val publishInfo = project.extensions.getByType(PublishInfo::class.java)
            val candidates = createAndroidReleaseVariantInfos(project)
            val publishableVariants = candidates.filter { publishInfo.shouldPublishVariant(it) }
            if (candidates.isNotEmpty() && publishableVariants.isEmpty()) {
                throw GradleException(
                    "No publishable Android release variants. Candidates: ${candidates.joinToString { it.name }}"
                )
            }
            publishableVariants.forEach { variant ->
                registerSingleVariant(androidDsl, variant.name)
            }
        })
    }

    private fun registerSingleVariant(androidDsl: Any, variantName: String) {
        val publishing = invokeNoArg(androidDsl, "getPublishing") ?: return
        val singleVariantMethod = publishing.javaClass.methods.firstOrNull { method ->
            method.name == "singleVariant" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: throw GradleException("Android publishing.singleVariant(String) is unavailable")
        singleVariantMethod.invoke(publishing, variantName)
        PluginLogUtil.printlnDebugInScreen("$TAG register android singleVariant $variantName")
    }

    private fun createAndroidReleaseVariantInfos(project: Project): List<PublishVariantInfo> {
        val buildTypeName = "release"
        val flavorSpecs = readAndroidFlavorSpecs(project)
        if (flavorSpecs.isEmpty()) {
            return listOf(
                PublishVariantInfo(
                    name = buildTypeName,
                    buildType = buildTypeName,
                    flavors = emptyMap()
                )
            )
        }

        val dimensions = readAndroidFlavorDimensions(project)
            .ifEmpty { flavorSpecs.map { it.dimension }.filter { it.isNotBlank() }.distinct() }
        val flavorsByDimension = dimensions.mapNotNull { dimension ->
            val flavors = flavorSpecs.filter { it.dimension == dimension }
            if (flavors.isEmpty()) {
                null
            } else {
                dimension to flavors
            }
        }
        if (flavorsByDimension.isEmpty()) {
            return emptyList()
        }

        return cartesianFlavorSpecs(flavorsByDimension.map { it.second }).map { flavors ->
            val flavorName = flavors.mapIndexed { index, flavor ->
                if (index == 0) flavor.name else flavor.name.capitalizeAscii()
            }.joinToString("")
            PublishVariantInfo(
                name = "$flavorName${buildTypeName.capitalizeAscii()}",
                buildType = buildTypeName,
                flavors = flavors.associate { it.dimension to it.name }
            )
        }
    }

    private fun cartesianFlavorSpecs(groups: List<List<AndroidFlavorSpec>>): List<List<AndroidFlavorSpec>> {
        return groups.fold(listOf(emptyList())) { combinations, group ->
            combinations.flatMap { combination ->
                group.map { flavor -> combination + flavor }
            }
        }
    }

    private fun resolveAndroidPublishTargets(
        project: Project,
        components: List<SoftwareComponent>,
        publishInfo: PublishInfo
    ): List<PublishTarget> {
        val buildTypeName = "release"
        val buildTypeComponents = components.filter { isBuildTypeComponent(it.name, buildTypeName) }
        val singleReleaseComponent =
            buildTypeComponents.size == 1 && buildTypeComponents.first().name.equals(buildTypeName, ignoreCase = true)

        return buildTypeComponents.map { component ->
            val publicationName = if (singleReleaseComponent) {
                MAVEN_PUBLICATION_NAME
            } else {
                "${component.name.capitalizeAscii()}$MAVEN_PUBLICATION_NAME"
            }
            val variantInfo = if (singleReleaseComponent) {
                null
            } else {
                createAndroidVariantInfo(project, component.name, buildTypeName)
            }
            val artifactId = publishInfo.resolveArtifactId(variantInfo)
            val groupId = publishInfo.resolveGroupId(variantInfo)
            val version = publishInfo.resolveVersion(variantInfo)
            PublishTarget(component, publicationName, groupId, artifactId, version)
        }
    }

    private fun isBuildTypeComponent(componentName: String, buildTypeName: String): Boolean {
        return componentName.equals(buildTypeName, ignoreCase = true) ||
            componentName.endsWith(buildTypeName.capitalizeAscii(), ignoreCase = false)
    }

    private fun createAndroidVariantInfo(
        project: Project,
        componentName: String,
        buildTypeName: String
    ): PublishVariantInfo {
        val flavors = parseFlavorsFromComponent(project, componentName, buildTypeName)
        return PublishVariantInfo(
            name = componentName,
            buildType = buildTypeName,
            flavors = flavors
                .filter { it.dimension.isNotBlank() }
                .associate { it.dimension to it.name }
        )
    }

    private fun parseFlavorsFromComponent(
        project: Project,
        componentName: String,
        buildTypeName: String
    ): List<AndroidFlavorSpec> {
        val flavorPart = removeBuildTypeSuffix(componentName, buildTypeName)
        if (flavorPart.isBlank()) {
            return emptyList()
        }

        val candidates = readAndroidFlavorSpecs(project)
            .sortedWith(compareByDescending<AndroidFlavorSpec> { it.name.length }.thenBy { it.name })
        val matched = mutableListOf<AndroidFlavorSpec>()
        var remaining = flavorPart
        while (remaining.isNotBlank()) {
            val match = candidates.firstOrNull { candidate ->
                matched.none { it.name == candidate.name } &&
                    remaining.startsWith(candidate.name, ignoreCase = true)
            } ?: break
            matched += match
            remaining = remaining.substring(match.name.length)
        }
        return matched
    }

    private fun removeBuildTypeSuffix(componentName: String, buildTypeName: String): String {
        if (componentName.equals(buildTypeName, ignoreCase = true)) {
            return ""
        }
        val capitalizedBuildType = buildTypeName.capitalizeAscii()
        return if (componentName.endsWith(capitalizedBuildType)) {
            componentName.removeSuffix(capitalizedBuildType)
        } else {
            componentName
        }
    }

    private fun readAndroidFlavorSpecs(project: Project): List<AndroidFlavorSpec> {
        val androidExtension = project.extensions.findByName("android") ?: return emptyList()
        val productFlavors = invokeNoArg(androidExtension, "getProductFlavors") as? Iterable<*> ?: return emptyList()
        return productFlavors.mapNotNull { flavor ->
            val name = invokeNoArg(flavor, "getName")?.toString().orEmpty()
            if (name.isBlank()) {
                null
            } else {
                AndroidFlavorSpec(
                    name = name,
                    dimension = invokeNoArg(flavor, "getDimension")?.toString().orEmpty()
                )
            }
        }
    }

    private fun readAndroidFlavorDimensions(project: Project): List<String> {
        val androidExtension = project.extensions.findByName("android") ?: return emptyList()
        val dimensions = invokeNoArg(androidExtension, "getFlavorDimensionList")
            ?: invokeNoArg(androidExtension, "getFlavorDimensions")
        return (dimensions as? Iterable<*>)
            ?.mapNotNull { it?.toString() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun invokeNoArg(target: Any?, methodName: String): Any? {
        if (target == null) {
            return null
        }
        return try {
            target.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    private fun configurePom(
        project: Project,
        publication: MavenPublication,
        publishInfo: PublishInfo,
        artifactId: String
    ) {
        publication.pom { pom ->
            val pomName = PublishConfigResolver.resolvePomName(project, publishInfo, artifactId)
            if (pomName.isNotBlank()) {
                pom.name.set(pomName)
            }

            val pomDescription = PublishConfigResolver.resolvePomDescription(project, publishInfo)
            if (pomDescription.isNotBlank()) {
                pom.description.set(pomDescription)
            }

            val pomUrl = PublishConfigResolver.resolvePomUrl(project, publishInfo)
            if (pomUrl.isNotBlank()) {
                pom.url.set(pomUrl)
            }

            val inceptionYear = PublishConfigResolver.resolvePublishInfoText(
                project, "pomInceptionYear", publishInfo, publishInfo.pomInceptionYear
            )
            if (inceptionYear.isNotBlank()) {
                pom.inceptionYear.set(inceptionYear)
            }

            pom.licenses { licenses ->
                licenses.license { license ->
                    license.name.set(
                        PublishConfigResolver.resolvePublishInfoText(
                            project, "licenseName", publishInfo, publishInfo.licenseName
                        )
                    )
                    license.url.set(
                        PublishConfigResolver.resolvePublishInfoText(
                            project, "licenseUrl", publishInfo, publishInfo.licenseUrl
                        )
                    )
                    license.distribution.set(
                        PublishConfigResolver.resolvePublishInfoText(
                            project, "licenseDistribution", publishInfo, publishInfo.licenseDistribution
                        )
                    )
                }
            }

            val developerValues = listOf(
                publishInfo.developerId,
                publishInfo.developerName,
                publishInfo.developerEmail,
                publishInfo.developerOrganization,
                publishInfo.developerOrganizationUrl,
                publishInfo.developerUrl
            )
            if (developerValues.any { it.isNotBlank() }) {
                pom.developers { developers ->
                    developers.developer { developer ->
                        val developerId = PublishConfigResolver.resolvePublishInfoText(
                            project, "developerId", publishInfo, publishInfo.developerId
                        )
                        if (developerId.isNotBlank()) {
                            developer.id.set(developerId)
                        }
                        val developerName = PublishConfigResolver.resolvePublishInfoText(
                            project, "developerName", publishInfo, publishInfo.developerName
                        )
                        if (developerName.isNotBlank()) {
                            developer.name.set(developerName)
                        }
                        val developerEmail = PublishConfigResolver.resolvePublishInfoText(
                            project, "developerEmail", publishInfo, publishInfo.developerEmail
                        )
                        if (developerEmail.isNotBlank()) {
                            developer.email.set(developerEmail)
                        }
                        val developerOrganization = PublishConfigResolver.resolvePublishInfoText(
                            project, "developerOrganization", publishInfo, publishInfo.developerOrganization
                        )
                        if (developerOrganization.isNotBlank()) {
                            developer.organization.set(developerOrganization)
                        }
                        val developerOrganizationUrl = PublishConfigResolver.resolvePublishInfoText(
                            project,
                            "developerOrganizationUrl",
                            publishInfo,
                            publishInfo.developerOrganizationUrl
                        )
                        if (developerOrganizationUrl.isNotBlank()) {
                            developer.organizationUrl.set(developerOrganizationUrl)
                        }
                        val developerUrl = PublishConfigResolver.resolvePublishInfoText(
                            project, "developerUrl", publishInfo, publishInfo.developerUrl
                        )
                        if (developerUrl.isNotBlank()) {
                            developer.url.set(developerUrl)
                        }
                    }
                }
            }

            val resolvedScmUrl = PublishConfigResolver.resolveScmUrl(project, publishInfo)
            val resolvedScmConnection = PublishConfigResolver.resolveScmConnection(project, publishInfo)
            val resolvedScmDeveloperConnection =
                PublishConfigResolver.resolveScmDeveloperConnection(project, publishInfo)
            val scmValues = listOf(resolvedScmUrl, resolvedScmConnection, resolvedScmDeveloperConnection)
            if (scmValues.any { it.isNotBlank() }) {
                pom.scm { scm ->
                    if (resolvedScmUrl.isNotBlank()) {
                        scm.url.set(resolvedScmUrl)
                    }
                    if (resolvedScmConnection.isNotBlank()) {
                        scm.connection.set(resolvedScmConnection)
                    }
                    if (resolvedScmDeveloperConnection.isNotBlank()) {
                        scm.developerConnection.set(resolvedScmDeveloperConnection)
                    }
                }
            }
        }
    }

    private fun configureCentralRepository(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo,
        properties: java.util.Properties
    ) {
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo, properties)
        val repositoryName = PublishConfigResolver.resolveCentralRepositoryName(project, publishInfo)
        publishing.repositories { artifactRepositories ->
            artifactRepositories.maven { repository ->
                repository.name = repositoryName
                repository.url = URI(PublishConfigResolver.CENTRAL_STAGING_URL)
                allowInsecureProtocolIfSupported(repository)
                repository.credentials { passwordCredentials ->
                    passwordCredentials.username = credentials.username
                    passwordCredentials.password = credentials.password
                }
            }
        }
        PluginLogUtil.printlnDebugInScreen("$TAG central repository is $repositoryName")
    }

    private fun configureCustomRepository(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo,
        properties: java.util.Properties
    ) {
        val publishUrl = PublishConfigResolver.resolveCustomRepositoryUrl(project, publishInfo, properties)
        if (publishUrl.isBlank()) {
            return
        }
        val publishUserName = PublishConfigResolver.resolveCustomRepositoryUsername(project, publishInfo, properties)
        val publishPassword = PublishConfigResolver.resolveCustomRepositoryPassword(project, publishInfo, properties)
        publishing.repositories { artifactRepositories ->
            artifactRepositories.maven { repository ->
                repository.name = "Maven"
                repository.url = URI(publishUrl)
                allowInsecureProtocolIfSupported(repository)
                repository.credentials { credentials ->
                    credentials.username = publishUserName
                    credentials.password = publishPassword
                }
            }
        }
        PluginLogUtil.printlnDebugInScreen("$TAG custom repository is $publishUrl")
    }

    private fun configureGitHubPackagesRepository(
        project: Project,
        publishing: PublishingExtension,
        publishInfo: PublishInfo,
        properties: java.util.Properties
    ) {
        val publishUrl = PublishConfigResolver.resolveGitHubPackagesUrl(project, publishInfo, properties)
        if (publishUrl.isBlank()) {
            return
        }
        val credentials = PublishConfigResolver.resolveGitHubPackagesCredentials(project, publishInfo, properties)
        val repositoryName = PublishConfigResolver.resolveGitHubPackagesRepositoryName(project, publishInfo)
        publishing.repositories { artifactRepositories ->
            artifactRepositories.maven { repository ->
                repository.name = repositoryName
                repository.url = URI(publishUrl)
                allowInsecureProtocolIfSupported(repository)
                repository.credentials { passwordCredentials ->
                    passwordCredentials.username = credentials.username
                    passwordCredentials.password = credentials.password
                }
            }
        }
        PluginLogUtil.printlnDebugInScreen("$TAG GitHub Packages repository is $publishUrl")
    }

    private fun configureSigning(project: Project, publication: MavenPublication) {
        val signingCredentials = PublishConfigResolver.resolveSigningCredentials(project)
        if (signingCredentials.key.isBlank()) {
            return
        }

        project.plugins.apply("signing")
        val signing = project.extensions.getByType(SigningExtension::class.java)
        if (signingCredentials.keyId.isBlank()) {
            signing.useInMemoryPgpKeys(signingCredentials.key, signingCredentials.password)
        } else {
            signing.useInMemoryPgpKeys(
                signingCredentials.keyId,
                signingCredentials.key,
                signingCredentials.password
            )
        }
        signing.sign(publication)
    }

    private fun allowInsecureProtocolIfSupported(repository: MavenArtifactRepository) {
        try {
            repository.javaClass.methods
                .firstOrNull { method ->
                    method.name == "setAllowInsecureProtocol" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == java.lang.Boolean.TYPE
                }
                ?.invoke(repository, true)
        } catch (_: Exception) {
            // Older Gradle versions do not expose allowInsecureProtocol.
        }
    }

    private fun skipSourcesVariants(project: Project, softwareComponent: SoftwareComponent) {
        val componentWithVariants = softwareComponent as? AdhocComponentWithVariants ?: return
        listOf("${softwareComponent.name}SourcesElements", "sourcesElements")
            .distinct()
            .mapNotNull { project.configurations.findByName(it) }
            .forEach { configuration ->
                try {
                    componentWithVariants.withVariantsFromConfiguration(configuration) { details ->
                        details.skip()
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("does not exist in component") == true) {
                        PluginLogUtil.printlnDebugInScreen(
                            "$TAG skip sources configuration ${configuration.name} for ${softwareComponent.name}: ${e.message}"
                        )
                    } else {
                        throw e
                    }
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

    private fun createJavadocJarTask(project: Project): Any {
        val taskName = "javadocJar"
        return project.tasks.findByName(taskName) ?: project.tasks.create(
            taskName, Jar::class.java
        ) { jar ->
            val javadocTask = project.tasks.findByName("javadoc")
            jar.archiveClassifier.set("javadoc")
            if (javadocTask != null) {
                jar.dependsOn(javadocTask)
                jar.from(javadocTask.outputs.files)
            } else {
                val emptyJavadocDir = File(project.buildDir, "empty-javadoc")
                jar.doFirst {
                    emptyJavadocDir.mkdirs()
                }
                jar.from(emptyJavadocDir)
            }
        }
    }

    private fun removeSourcesArtifacts(publication: MavenPublication) {
        publication.artifacts.toList()
            .filter { it.classifier == "sources" }
            .forEach { publication.artifacts.remove(it) }
    }

    private fun String.capitalizeAscii(): String {
        if (isEmpty()) {
            return this
        }
        val first = this[0]
        val capitalizedFirst = if (first in 'a'..'z') first - 32 else first
        return "$capitalizedFirst${substring(1)}"
    }

}
