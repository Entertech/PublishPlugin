package custom.android.plugin;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublishPluginFunctionalTest {
    private static final String TEST_GRADLE_VERSION = "8.7";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void publishToMavenLocalDoesNotPublishSourcesJarForNonDebugVersion() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0", true);
        File mavenLocal = temporaryFolder.newFolder("maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        Path versionDir = mavenLocal.toPath().resolve("com/example/fixture/1.0.0");
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0.jar")));
        assertFalse(Files.exists(versionDir.resolve("fixture-1.0.0-sources.jar")));
        assertFalse(read(versionDir.resolve("fixture-1.0.0.module")).contains("SourcesElements"));
    }

    @Test
    public void publishToMavenLocalPublishesSourcesJarForDebugVersion() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0-debug", false);
        File mavenLocal = temporaryFolder.newFolder("debug-maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        Path versionDir = mavenLocal.toPath().resolve("com/example/fixture/1.0.0-debug");
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-debug.jar")));
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-debug-sources.jar")));
    }

    @Test
    public void centralModeAddsCentralRepositoryAndPomMetadataCanBeOverriddenFromCli() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0", false, centralPublishInfo(), "");

        String tasksOutput = gradleRunner(projectDir)
                .withArguments(
                        ":fixture:tasks",
                        "--all",
                        "-PcentralPublish=true",
                        "-PcentralNamespace=com.example.cli",
                        "-PcentralUsername=token-user",
                        "-PcentralPassword=token-password",
                        "--stacktrace"
                )
                .build()
                .getOutput();

        assertTrue(tasksOutput.contains("publishEnterPublishPublicationToCentralStagingRepository"));

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:generatePomFileForEnterPublishPublication",
                        "-PcentralPublish=true",
                        "-PcentralNamespace=com.example.cli",
                        "-PpomName=CLI Fixture",
                        "-PpomDescription=Configured from CLI",
                        "-PpomUrl=https://example.com/cli",
                        "--stacktrace"
                )
                .build();

        String pom = read(projectDir.toPath().resolve("fixture/build/publications/EnterPublish/pom-default.xml"));
        assertTrue(pom.contains("<name>CLI Fixture</name>"));
        assertTrue(pom.contains("<description>Configured from CLI</description>"));
        assertTrue(pom.contains("<url>https://example.com/cli</url>"));
        assertTrue(pom.contains("<email>dev@example.com</email>"));
        assertTrue(pom.contains("<organization>Example Org</organization>"));
        assertTrue(pom.contains("<connection>scm:git:git://example.com/fixture.git</connection>"));
    }

    @Test
    public void centralModePublishesSourcesAndJavadocsToMavenLocalForReleaseVersion() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0", false, centralPublishInfo(), "");
        File mavenLocal = temporaryFolder.newFolder("central-maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-PcentralPublish=true",
                        "-PcentralUsername=token-user",
                        "-PcentralPassword=token-password",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        Path versionDir = mavenLocal.toPath().resolve("com/example/fixture/1.0.0");
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0.jar")));
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-sources.jar")));
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-javadoc.jar")));
    }

    @Test
    public void remoteTaskUsesCliNamespaceAndLegacyCredentialsBeforeSigningValidation() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0", false, centralPublishInfo("org.wrong"), "");

        String output = gradleRunner(projectDir)
                .withArguments(
                        ":fixture:PublishLibraryRemoteTask",
                        "-PcentralNamespace=com.example",
                        "--stacktrace"
                )
                .buildAndFail()
                .getOutput();

        assertTrue(output.contains("signingInMemoryKey"));
        assertFalse(output.contains("must be under centralNamespace"));
        assertFalse(output.contains("centralUsername/centralPassword"));
    }

    @Test
    public void androidLibraryCreatesReleaseFlavorPublicationsWithDynamicArtifactIds() throws IOException {
        File projectDir = createAndroidLibraryProjectWithPublishFlavors();

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:generatePomFileForBreathAuthReleaseEnterPublishPublication",
                        ":fixture:generatePomFileForBreathNoAuthReleaseEnterPublishPublication",
                        ":fixture:generatePomFileForSdkAuthReleaseEnterPublishPublication",
                        ":fixture:generatePomFileForSdkNoAuthReleaseEnterPublishPublication",
                        "--stacktrace"
                )
                .build();

        assertPomContainsArtifactId(projectDir.toPath(), "BreathAuthReleaseEnterPublish", "breath-affective-offline-sdk-authentication");
        assertPomContainsArtifactId(projectDir.toPath(), "BreathNoAuthReleaseEnterPublish", "breath-affective-offline-sdk");
        assertPomContainsArtifactId(projectDir.toPath(), "SdkAuthReleaseEnterPublish", "sdk-affective-offline-sdk-authentication");
        assertPomContainsArtifactId(projectDir.toPath(), "SdkNoAuthReleaseEnterPublish", "sdk-affective-offline-sdk");
    }

    @Test
    public void androidLibraryPublishesReleaseFlavorVariantsToMavenLocalWithMetadata() throws IOException {
        File projectDir = createAndroidLibraryProjectWithPublishFlavors();
        File mavenLocal = temporaryFolder.newFolder("android-flavor-maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk-authentication");
        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk");
        assertMavenAarPublication(mavenLocal.toPath(), "sdk-affective-offline-sdk-authentication");
        assertMavenAarPublication(mavenLocal.toPath(), "sdk-affective-offline-sdk");
    }

    @Test
    public void androidLibrarySkipsFilteredReleaseFlavorVariants() throws IOException {
        File projectDir = createAndroidLibraryProjectWithPublishFlavors(
                "    skipVariantIf { variant ->\n"
                        + "        return variant.flavor('project') == 'sdk'\n"
                        + "    }\n"
        );
        File mavenLocal = temporaryFolder.newFolder("android-flavor-filtered-maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk-authentication");
        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk");
        assertMavenArtifactMissing(mavenLocal.toPath(), "sdk-affective-offline-sdk-authentication");
        assertMavenArtifactMissing(mavenLocal.toPath(), "sdk-affective-offline-sdk");
    }

    @Test
    public void androidLibraryIgnoresSourcesConfigurationsThatAreNotComponentVariants() throws IOException {
        File projectDir = createAndroidLibraryProjectWithPublishFlavors(
                "",
                "configurations.maybeCreate('breathAuthReleaseSourcesElements')\n"
        );
        File mavenLocal = temporaryFolder.newFolder("android-flavor-extra-sources-config-maven-local");

        gradleRunner(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .build();

        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk-authentication");
        assertMavenAarPublication(mavenLocal.toPath(), "breath-affective-offline-sdk");
        assertMavenAarPublication(mavenLocal.toPath(), "sdk-affective-offline-sdk-authentication");
        assertMavenAarPublication(mavenLocal.toPath(), "sdk-affective-offline-sdk");
    }

    private File createGradlePluginProject(String version, boolean componentPublishesSources) throws IOException {
        return createGradlePluginProject(version, componentPublishesSources, "", "");
    }

    private File createGradlePluginProject(
            String version,
            boolean componentPublishesSources,
            String publishInfoExtra,
            String localProperties
    ) throws IOException {
        File root = temporaryFolder.newFolder("project-" + version);
        write(root.toPath().resolve("settings.gradle"), "pluginManagement {\n"
                + "    repositories { google(); mavenCentral(); gradlePluginPortal() }\n"
                + "}\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories { google(); mavenCentral() }\n"
                + "}\n"
                + "rootProject.name = 'publish-plugin-fixture'\n"
                + "include ':fixture'\n");
        write(root.toPath().resolve("local.properties"), localProperties);

        Path fixtureDir = root.toPath().resolve("fixture");
        Files.createDirectories(fixtureDir.resolve("src/main/java/com/example/fixture"));
        write(fixtureDir.resolve("src/main/java/com/example/fixture/FixturePlugin.java"),
                "package com.example.fixture;\n"
                        + "import org.gradle.api.Plugin;\n"
                        + "import org.gradle.api.Project;\n"
                        + "public class FixturePlugin implements Plugin<Project> {\n"
                        + "    public void apply(Project project) { }\n"
                        + "}\n");

        String publishSources = componentPublishesSources ? "java { withSourcesJar() }\n" : "";
        write(fixtureDir.resolve("build.gradle"), "plugins {\n"
                + "    id 'groovy'\n"
                + "    id 'java-gradle-plugin'\n"
                + "    id 'custom.android.plugin'\n"
                + "}\n"
                + "PublishInfo {\n"
                + "    groupId = 'com.example'\n"
                + "    artifactId = 'fixture'\n"
                + "    version = '" + version + "'\n"
                + "    pluginId = 'com.example.fixture'\n"
                + "    implementationClass = 'com.example.fixture.FixturePlugin'\n"
                + publishInfoExtra
                + "}\n"
                + publishSources);
        return root;
    }

    private File createAndroidLibraryProjectWithPublishFlavors() throws IOException {
        return createAndroidLibraryProjectWithPublishFlavors("");
    }

    private File createAndroidLibraryProjectWithPublishFlavors(String publishInfoExtra) throws IOException {
        return createAndroidLibraryProjectWithPublishFlavors(publishInfoExtra, "");
    }

    private File createAndroidLibraryProjectWithPublishFlavors(
            String publishInfoExtra,
            String buildScriptExtra
    ) throws IOException {
        File root = temporaryFolder.newFolder("android-flavor-project");
        write(root.toPath().resolve("settings.gradle"), "pluginManagement {\n"
                + "    repositories { google(); mavenCentral(); gradlePluginPortal() }\n"
                + "}\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories { google(); mavenCentral() }\n"
                + "}\n"
                + "rootProject.name = 'android-flavor-fixture'\n"
                + "include ':fixture'\n");
        write(root.toPath().resolve("local.properties"), "");

        Path fixtureDir = root.toPath().resolve("fixture");
        Files.createDirectories(fixtureDir.resolve("src/main/java/com/example/fixture"));
        write(fixtureDir.resolve("src/main/java/com/example/fixture/Fixture.java"),
                "package com.example.fixture;\n"
                        + "public class Fixture {\n"
                        + "    public String value() { return \"fixture\"; }\n"
                        + "}\n");
        write(fixtureDir.resolve("build.gradle"), "plugins {\n"
                + "    id 'com.android.library' version '8.1.3'\n"
                + "    id 'custom.android.plugin'\n"
                + "}\n"
                + "android {\n"
                + "    namespace 'com.example.fixture'\n"
                + "    compileSdk 34\n"
                + "    defaultConfig { minSdk 23 }\n"
                + "    flavorDimensions 'project', 'authentication'\n"
                + "    productFlavors {\n"
                + "        breath { dimension 'project' }\n"
                + "        sdk { dimension 'project' }\n"
                + "        auth { dimension 'authentication' }\n"
                + "        noAuth { dimension 'authentication' }\n"
                + "    }\n"
                + "}\n"
                + "def baseArtifactId = 'affective-offline-sdk'\n"
                + "PublishInfo {\n"
                + "    groupId = 'com.example'\n"
                + "    artifactId = baseArtifactId\n"
                + "    version = '1.0.0'\n"
                + "    artifactIdForVariant { variant ->\n"
                + "        def productPrefix = \"${variant.flavor('project')}-\"\n"
                + "        def authSuffix = variant.flavor('authentication') == 'auth' ? '-authentication' : ''\n"
                + "        return \"${productPrefix}${baseArtifactId}${authSuffix}\"\n"
                + "    }\n"
                + publishInfoExtra
                + "}\n"
                + buildScriptExtra);
        return root;
    }

    private static void assertPomContainsArtifactId(Path projectDir, String publicationName, String artifactId)
            throws IOException {
        Path pomPath = projectDir.resolve("fixture/build/publications/" + publicationName + "/pom-default.xml");
        assertTrue("Missing POM for " + publicationName, Files.exists(pomPath));
        assertTrue(read(pomPath).contains("<artifactId>" + artifactId + "</artifactId>"));
    }

    private static void assertMavenAarPublication(Path mavenLocal, String artifactId) throws IOException {
        Path versionDir = mavenLocal.resolve("com/example/" + artifactId + "/1.0.0");
        assertTrue("Missing AAR for " + artifactId, Files.exists(versionDir.resolve(artifactId + "-1.0.0.aar")));
        assertTrue("Missing POM for " + artifactId, Files.exists(versionDir.resolve(artifactId + "-1.0.0.pom")));
        assertTrue("Missing module metadata for " + artifactId, Files.exists(versionDir.resolve(artifactId + "-1.0.0.module")));
        assertFalse("Non-debug publish should not include sources for " + artifactId,
                Files.exists(versionDir.resolve(artifactId + "-1.0.0-sources.jar")));
    }

    private static void assertMavenArtifactMissing(Path mavenLocal, String artifactId) {
        Path versionDir = mavenLocal.resolve("com/example/" + artifactId + "/1.0.0");
        assertFalse("Unexpected Maven publication for " + artifactId, Files.exists(versionDir));
    }

    private static String centralPublishInfo() {
        return centralPublishInfo("com.example");
    }

    private static String centralPublishInfo(String namespace) {
        return ""
                + "    publishUserName = 'legacy-user'\n"
                + "    publishPassword = 'legacy-password'\n"
                + "    centralNamespace = '" + namespace + "'\n"
                + "    centralPublishingType = 'user_managed'\n"
                + "    pomName = 'Fixture'\n"
                + "    pomDescription = 'Fixture publish plugin test'\n"
                + "    pomInceptionYear = '2026'\n"
                + "    pomUrl = 'https://example.com/fixture'\n"
                + "    developerId = 'example'\n"
                + "    developerName = 'Example Developer'\n"
                + "    developerEmail = 'dev@example.com'\n"
                + "    developerOrganization = 'Example Org'\n"
                + "    developerOrganizationUrl = 'https://example.com'\n"
                + "    developerUrl = 'https://example.com/dev'\n"
                + "    scmUrl = 'https://example.com/fixture'\n"
                + "    scmConnection = 'scm:git:git://example.com/fixture.git'\n"
                + "    scmDeveloperConnection = 'scm:git:ssh://git@example.com/fixture.git'\n";
    }

    private static void write(Path path, String value) throws IOException {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static GradleRunner gradleRunner(File projectDir) {
        return GradleRunner.create()
                .withGradleVersion(TEST_GRADLE_VERSION)
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .forwardOutput();
    }
}
