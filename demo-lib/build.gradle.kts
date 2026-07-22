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
    groupId = "cn.entertech.android"
    artifactId = baseArtifactId
    version = "1.0.2"

    pomName = "Entertech Publish Demo Library"
    pomDescription = "Android Library demo for cn.entertech.publish with multiple release variants."
    pomUrl = "https://github.com/Entertech/PublishPlugin"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
