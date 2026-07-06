plugins {
    id("cn.entertech.publish")
    `java-gradle-plugin`
}

group = "cn.entertech.android.demo"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

PublishInfo {
    groupId = "cn.entertech.android.demo"
    artifactId = "demo-publish-plugin"
    version = "1.0.0"

    pluginId = "cn.entertech.plugin.demo"
    implementationClass = "cn.entertech.plugin.demo.plugin.DemoPublishPlugin"

    centralNamespace = "cn.entertech"
    centralPublishingType = "user_managed"

    pomName = "Entertech Publish Demo Gradle Plugin"
    pomDescription = "Gradle Plugin demo for publishing with cn.entertech.publish."
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
}

dependencies {
    implementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}
