import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    groovy
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
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

gradlePlugin {
    plugins {
        create("publish") {
            id = "custom.android.plugin"
            implementationClass = "custom.android.plugin.PublishPlugin"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            groupId = "cn.entertech.android"
            artifactId = "publish"
            version = "1.2.0"
            from(components["java"])
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
