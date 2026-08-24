package custom.android.plugin

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

open class PublishRepositories @Inject constructor(objects: ObjectFactory) {
    val githubPackages: PublishRepository = PublishRepository(objects)
    val central: PublishCentralRepository = PublishCentralRepository(objects)

    fun githubPackages(action: Action<in PublishRepository>) = action.execute(githubPackages)
    fun githubPackages(action: PublishRepository.() -> Unit) = githubPackages.action()
    fun central(action: Action<in PublishCentralRepository>) = action.execute(central)
    fun central(action: PublishCentralRepository.() -> Unit) = central.action()

    fun isGithubPackagesEnabled(): Boolean = githubPackages.enabled.get()

    fun isCentralEnabled(): Boolean = central.enabled.get()

    fun enabledRemoteProviderIds(): List<String> = buildList {
        if (isGithubPackagesEnabled()) add(PublishConfigResolver.MODE_GITHUB_PACKAGES)
        if (isCentralEnabled()) add(PublishConfigResolver.MODE_CENTRAL)
    }
}

open class PublishRepository @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val repository: Property<String> = objects.property(String::class.java).convention("")
    val repositoryUrl: Property<String> = objects.property(String::class.java).convention("")
    val repositoryName: Property<String> = objects.property(String::class.java).convention("")
}

open class PublishCentralRepository @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val namespace: Property<String> = objects.property(String::class.java).convention("")
    val publishingType: Property<String> = objects.property(String::class.java).convention("user_managed")
    val releaseRepositoryName: Property<String> = objects.property(String::class.java).convention("")
    val snapshotRepositoryName: Property<String> = objects.property(String::class.java).convention("")
}
