import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    groovy
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
    signing
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

group = "cn.entertech.android"
val baseVersion = "2.0.1"
val requestedTaskNames = generateSequence(gradle) { currentGradle -> currentGradle.parent }
    .flatMap { currentGradle -> currentGradle.startParameter.taskNames.asSequence() }
val localPublishRequested = requestedTaskNames.any { taskName ->
    val shortTaskName = taskName.substringAfterLast(":")
    shortTaskName == "publishToMavenLocal" || shortTaskName.endsWith("PublicationToMavenLocal")
}
version = if (localPublishRequested && !baseVersion.endsWith("-local")) {
    "$baseVersion-local"
} else {
    baseVersion
}

base {
    archivesName.set("publish")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    compileOnly("com.android.tools.build:gradle:4.2.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

fun MavenPublication.configureCentralPomMetadata() {
    pom {
        name.set("Entertech Publish Gradle Plugin")
        description.set(
            "Gradle plugin for publishing Android libraries and Gradle plugins to Maven Local and Sonatype Central Portal."
        )
        url.set("https://github.com/Entertech/PublishPlugin")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Entertech")
                name.set("Entertech")
                email.set("developer@entertech.cn")
                organization.set("Entertech")
                organizationUrl.set("https://github.com/Entertech")
                url.set("https://github.com/Entertech")
            }
        }

        scm {
            url.set("https://github.com/Entertech/PublishPlugin")
            connection.set("scm:git:git://github.com/Entertech/PublishPlugin.git")
            developerConnection.set("scm:git:ssh://git@github.com/Entertech/PublishPlugin.git")
        }
    }
}

gradlePlugin {
    plugins {
        create("publish") {
            id = "cn.entertech.publish"
            implementationClass = "custom.android.plugin.PublishPlugin"
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            if (name == "pluginMaven") {
                artifactId = "publish"
            }
            configureCentralPomMetadata()
        }
    }

    repositories {
        maven {
            name = "CentralStaging"
            url = uri(
                providers.gradleProperty("publishUrl")
                    .orElse("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    .get()
            )
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("centralUsername")
                    .orElse(providers.environmentVariable("CENTRAL_USERNAME"))
                    .orElse(providers.gradleProperty("mavenCentralUsername"))
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                    .orElse(providers.gradleProperty("publishUserName"))
                    .orElse("")
                    .get()
                password = providers.gradleProperty("centralPassword")
                    .orElse(providers.environmentVariable("CENTRAL_PASSWORD"))
                    .orElse(providers.gradleProperty("mavenCentralPassword"))
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                    .orElse(providers.gradleProperty("publishPassword"))
                    .orElse("")
                    .get()
            }
        }
        maven {
            name = "CentralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("centralUsername")
                    .orElse(providers.environmentVariable("CENTRAL_USERNAME"))
                    .orElse(providers.gradleProperty("mavenCentralUsername"))
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                    .orElse(providers.gradleProperty("publishUserName"))
                    .orElse("")
                    .get()
                password = providers.gradleProperty("centralPassword")
                    .orElse(providers.environmentVariable("CENTRAL_PASSWORD"))
                    .orElse(providers.gradleProperty("mavenCentralPassword"))
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                    .orElse(providers.gradleProperty("publishPassword"))
                    .orElse("")
                    .get()
            }
        }
    }
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        configureCentralPomMetadata()
    }
}

tasks.matching {
    it.name == "publishToMavenLocal" || it.name.endsWith("PublicationToMavenLocal")
}.configureEach {
    doLast {
        val repository = File(
            System.getProperty("maven.repo.local")
                ?: File(System.getProperty("user.home"), ".m2/repository").path
        ).canonicalFile
        println("Maven Local repository: ${repository.toURI()}")
        publishing.publications.withType<MavenPublication>().forEach { publication ->
            val directory = File(
                repository,
                "${publication.groupId.replace('.', File.separatorChar)}${File.separator}" +
                    "${publication.artifactId}${File.separator}${publication.version}"
            ).toURI()
            println(
                "Maven Local publication: ${publication.groupId}:${publication.artifactId}:${publication.version}"
            )
            println("Maven Local address: $directory")
        }
    }
}

val centralPublishRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("CentralStagingRepository") ||
        taskName.contains("CentralSnapshotsRepository") ||
        taskName.contains("publishAllPublicationsToCentralStagingRepository") ||
        taskName.contains("publishAllPublicationsToCentralSnapshotsRepository")
} || providers.gradleProperty("centralPublish").orNull.equals("true", ignoreCase = true)

signing {
    isRequired = centralPublishRequested

    val signingKey = providers.gradleProperty("signingInMemoryKey")
        .orElse(providers.environmentVariable("SIGNING_IN_MEMORY_KEY"))
        .orElse(providers.environmentVariable("GPG_KEY_CONTENTS"))
        .orNull
    val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
        .orElse(providers.environmentVariable("SIGNING_IN_MEMORY_KEY_PASSWORD"))
        .orElse(providers.gradleProperty("signingPassword"))
        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
        .orNull
    val signingKeyId = providers.gradleProperty("signingInMemoryKeyId")
        .orElse(providers.environmentVariable("SIGNING_IN_MEMORY_KEY_ID"))
        .orElse(providers.gradleProperty("signingKeyId"))
        .orElse(providers.environmentVariable("SIGNING_KEY_ID"))
        .orNull

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        if (signingKeyId.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }
    }

    sign(publishing.publications)
}
