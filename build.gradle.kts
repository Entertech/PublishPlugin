// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        // Keep the locally built PublishPlugin resolvable when artifact bundle
        // preparation temporarily redirects maven.repo.local to an isolated directory.
        maven {
            name = "DeveloperMavenLocal"
            url = uri("${System.getProperty("user.home")}/.m2/repository")
        }
        mavenLocal()
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.1.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0")
        classpath("cn.entertech.android:publish:1.2.4-local")
    }
}
