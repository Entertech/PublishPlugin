plugins {
    id("cn.entertech.publish")
    `java-gradle-plugin`
}

group = "cn.entertech.android"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

PublishInfo {
    groupId = "cn.entertech.android"
    artifactId = "demo-publish-plugin"
    version = "1.0.0"

    pluginId = "cn.entertech.plugin.demo"
    implementationClass = "cn.entertech.plugin.demo.plugin.DemoPublishPlugin"

    pomName = "Entertech Publish Demo Gradle Plugin"
    pomDescription = "Gradle Plugin demo for publishing with cn.entertech.publish."
    pomUrl = "https://github.com/Entertech/PublishPlugin"
}

dependencies {
    implementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}
