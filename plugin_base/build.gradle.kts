import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    groovy
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
    signing
}

fun publishProperty(name: String): String = providers.gradleProperty(name).get()

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

group = publishProperty("publishGroup")
version = publishProperty("publishVersion")

base {
    archivesName.set(publishProperty("publishArtifactId"))
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
        name.set(publishProperty("publishDisplayName"))
        description.set(publishProperty("publishDescription"))
        url.set(publishProperty("publishWebsite"))
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
                id.set(publishProperty("publishDeveloperId"))
                name.set(publishProperty("publishDeveloperName"))
                email.set(publishProperty("publishDeveloperEmail"))
                organization.set(publishProperty("publishDeveloperOrganization"))
                organizationUrl.set(publishProperty("publishDeveloperOrganizationUrl"))
                url.set(publishProperty("publishDeveloperUrl"))
            }
        }

        scm {
            url.set(publishProperty("publishScmUrl"))
            connection.set(publishProperty("publishScmConnection"))
            developerConnection.set(publishProperty("publishScmDeveloperConnection"))
        }
    }
}

gradlePlugin {
    plugins {
        create("publish") {
            id = publishProperty("publishPluginId")
            implementationClass = "custom.android.plugin.PublishPlugin"
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            if (name == "pluginMaven") {
                artifactId = publishProperty("publishArtifactId")
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
    }
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        configureCentralPomMetadata()
    }
}

val centralPublishRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("CentralStagingRepository") ||
        taskName.contains("publishAllPublicationsToCentralStagingRepository")
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
