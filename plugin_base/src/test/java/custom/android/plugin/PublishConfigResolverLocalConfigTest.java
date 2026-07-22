package custom.android.plugin;

import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PublishConfigResolverLocalConfigTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void localPublishValuesAreFallbackBehindExplicitPublishInfo() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        Files.write(new File(rootDir, "local.properties").toPath(), (
                "publish.centralNamespace=cn.local\n"
                        + "publish.centralPublishingType=automatic\n"
        ).getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();

        assertEquals("cn.local", PublishConfigResolver.INSTANCE.resolveCentralNamespace(project, publishInfo));
        assertEquals("automatic", PublishConfigResolver.INSTANCE.resolveCentralPublishingType(project, publishInfo));

        publishInfo.setCentralNamespace("cn.explicit");

        assertEquals("cn.explicit", PublishConfigResolver.INSTANCE.resolveCentralNamespace(project, publishInfo));
    }

    @Test
    public void localCentralPublishTargetSelectsCentralRemoteMode() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        Files.write(new File(rootDir, "local.properties").toPath(),
                "publish.publishTarget=central\n".getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();

        assertEquals(
                PublishConfigResolver.MODE_CENTRAL,
                PublishConfigResolver.INSTANCE.resolveRemotePublishMode(project, publishInfo)
        );
    }

    @Test
    public void legacyRemoteModePropertyCanOverrideLocalPublishTarget() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        Files.write(new File(rootDir, "local.properties").toPath(),
                "publish.publishTarget=central\n".getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();
        project.getExtensions().getExtraProperties().set("remotePublishMode", PublishConfigResolver.MODE_GITHUB_PACKAGES);

        assertEquals(
                PublishConfigResolver.MODE_GITHUB_PACKAGES,
                PublishConfigResolver.INSTANCE.resolveRemotePublishMode(project, publishInfo)
        );
    }

    @Test
    public void githubPackagesUrlUsesPublishPrefixedLocalRepository() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        Files.write(new File(rootDir, "local.properties").toPath(),
                "publish.githubPackagesRepository=Entertech/demo\n".getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();

        assertEquals(
                "https://maven.pkg.github.com/Entertech/demo",
                PublishConfigResolver.INSTANCE.resolveGitHubPackagesUrl(project, publishInfo, new Properties())
        );
    }

    @Test
    public void githubPackagesUrlFallsBackToNonOriginGitHubRemote() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File gitDir = new File(rootDir, ".git");
        assertEquals(true, gitDir.mkdirs());
        Files.write(new File(gitDir, "config").toPath(), (
                "[remote \"enter\"]\n"
                        + "    url = https://github.com/Entertech/PublishPlugin.git\n"
        ).getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();

        assertEquals(
                "https://maven.pkg.github.com/Entertech/PublishPlugin",
                PublishConfigResolver.INSTANCE.resolveGitHubPackagesUrl(project, publishInfo, new Properties())
        );
    }

    @Test
    public void githubPackagesUrlFailsWhenMultipleGitHubRemotesExist() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File gitDir = new File(rootDir, ".git");
        assertEquals(true, gitDir.mkdirs());
        Files.write(new File(gitDir, "config").toPath(), (
                "[remote \"enter\"]\n"
                        + "    url = https://github.com/Entertech/PublishPlugin.git\n"
                        + "[remote \"fork\"]\n"
                        + "    url = git@github.com:wk1995/PublishPlugin.git\n"
        ).getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();
        PublishInfo publishInfo = new PublishInfo();

        GradleException error = assertThrows(
                GradleException.class,
                () -> PublishConfigResolver.INSTANCE.resolveGitHubPackagesUrl(project, publishInfo, new Properties())
        );

        assertEquals(true, error.getMessage().contains("Multiple GitHub git remotes found"));
        assertEquals(true, error.getMessage().contains("enter=https://github.com/Entertech/PublishPlugin.git"));
        assertEquals(true, error.getMessage().contains("fork=git@github.com:wk1995/PublishPlugin.git"));
        assertEquals(true, error.getMessage().contains("publish.githubPackagesRepository=owner/repo"));
    }

    @Test
    public void pomDefaultsUseArtifactIdDescriptionAndGitUrl() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File gitDir = new File(rootDir, ".git");
        assertEquals(true, gitDir.mkdirs());
        Files.write(new File(gitDir, "config").toPath(), (
                "[remote \"origin\"]\n"
                        + "    url = git@github.com:Entertech/demo.git\n"
        ).getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).withName("fixture").build();
        project.getPluginManager().apply("java-gradle-plugin");
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setArtifactId("demo-plugin");

        assertEquals("demo-plugin", PublishConfigResolver.INSTANCE.resolvePomName(project, publishInfo, "demo-plugin"));
        assertEquals(
                "Gradle plugin published as a Maven artifact",
                PublishConfigResolver.INSTANCE.resolvePomDescription(project, publishInfo)
        );
        assertEquals("https://github.com/Entertech/demo", PublishConfigResolver.INSTANCE.resolvePomUrl(project, publishInfo));
    }

    @Test
    public void componentFieldsInLocalConfigFailDuringResolution() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        Files.write(new File(rootDir, "local.properties").toPath(),
                "publish.pomUrl=https://example.com\n".getBytes(StandardCharsets.UTF_8));
        Project project = ProjectBuilder.builder().withProjectDir(rootDir).build();

        assertThrows(
                IllegalArgumentException.class,
                () -> PublishConfigResolver.INSTANCE.loadPublishProperties(project)
        );
    }
}
