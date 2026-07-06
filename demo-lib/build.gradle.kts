plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("cn.entertech.publish")
}

val baseArtifactId = "publish-demo-lib"

android {
    namespace = "cn.entertech.plugin.demo.lib"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    flavorDimensions += listOf("project", "authentication")
    productFlavors {
        create("breath") {
            dimension = "project"
        }
        create("sdk") {
            dimension = "project"
        }
        create("auth") {
            dimension = "authentication"
        }
        create("noAuth") {
            dimension = "authentication"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

}

PublishInfo {
    groupId = "cn.entertech.android.demo"
    artifactId = baseArtifactId
    version = "1.0.0"

    centralNamespace = "cn.entertech"
    centralPublishingType = "user_managed"

    pomName = "Entertech Publish Demo Library"
    pomDescription = "Android Library demo for cn.entertech.publish with multiple release variants."
    pomInceptionYear = "2026"
    pomUrl = "https://github.com/Entertech/PublishPlugin"

    developerId = "Entertech"
    developerName = "Entertech"
    developerEmail = "developer@entertech.cn"
    developerOrganization = "Entertech"
    developerOrganizationUrl = "https://github.com/Entertech"
    developerUrl = "https://github.com/Entertech"

    scmUrl = "https://github.com/Entertech/PublishPlugin"
    scmConnection = "scm:git:git://github.com/Entertech/PublishPlugin.git"
    scmDeveloperConnection = "scm:git:ssh://git@github.com/Entertech/PublishPlugin.git"

    artifactIdForVariant { variant ->
        val productPrefix = "${variant.flavor("project")}-"
        val authSuffix = if (variant.flavor("authentication") == "auth") {
            "-authentication"
        } else {
            ""
        }

        "$productPrefix$baseArtifactId$authSuffix"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
