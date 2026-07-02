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
