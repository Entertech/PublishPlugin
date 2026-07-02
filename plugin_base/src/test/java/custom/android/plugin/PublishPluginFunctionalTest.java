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
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void publishToMavenLocalDoesNotPublishSourcesJarForNonDebugVersion() throws IOException {
        File projectDir = createGradlePluginProject("1.0.0", true);
        File mavenLocal = temporaryFolder.newFolder("maven-local");

        GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .withPluginClasspath()
                .forwardOutput()
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

        GradleRunner.create()
                .withProjectDir(projectDir)
                .withArguments(
                        ":fixture:publishToMavenLocal",
                        "-Dmaven.repo.local=" + mavenLocal.getAbsolutePath(),
                        "--stacktrace"
                )
                .withPluginClasspath()
                .forwardOutput()
                .build();

        Path versionDir = mavenLocal.toPath().resolve("com/example/fixture/1.0.0-debug");
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-debug.jar")));
        assertTrue(Files.exists(versionDir.resolve("fixture-1.0.0-debug-sources.jar")));
    }

    private File createGradlePluginProject(String version, boolean componentPublishesSources) throws IOException {
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
        write(root.toPath().resolve("local.properties"), "");

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
                + "}\n"
                + publishSources);
        return root;
    }

    private static void write(Path path, String value) throws IOException {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
