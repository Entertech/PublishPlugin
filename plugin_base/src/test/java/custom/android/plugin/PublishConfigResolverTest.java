package custom.android.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublishConfigResolverTest {
    @Test
    public void scmConnectionsCanBeDerivedFromConfiguredScmUrl() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setScmUrl("https://github.com/Entertech/PublishPlugin");

        assertEquals(
                "scm:git:https://github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmConnection(project, publishInfo)
        );
        assertEquals(
                "scm:git:ssh://git@github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmDeveloperConnection(project, publishInfo)
        );
    }

    @Test
    public void centralSnapshotReleaseTypeUsesSnapshotRepository() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        project.getExtensions().getExtraProperties().set("centralReleaseType", "snapshot");

        assertEquals(
                "CentralSnapshots",
                PublishConfigResolver.INSTANCE.resolveCentralRepositoryName(project, publishInfo)
        );
        assertEquals(
                PublishConfigResolver.CENTRAL_SNAPSHOT_URL,
                PublishConfigResolver.INSTANCE.resolveCentralRepositoryUrl(project)
        );
    }

    @Test
    public void scmConnectionsCanBeDerivedFromSshRemoteStyleUrl() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setScmUrl("git@github.com:Entertech/PublishPlugin.git");

        assertEquals(
                "scm:git:https://github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmConnection(project, publishInfo)
        );
        assertEquals(
                "scm:git:ssh://git@github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmDeveloperConnection(project, publishInfo)
        );
    }

    @Test
    public void obfuscateDefaultsToTrueAndSkipsSourcesForReleaseVersions() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setVersion("1.0.0");

        assertTrue(PublishConfigResolver.INSTANCE.resolveObfuscate(project, publishInfo));
        assertFalse(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0-debug"));
    }

    @Test
    public void obfuscateFalsePublishesSourcesForReleaseVersions() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setObfuscate(false);

        assertFalse(PublishConfigResolver.INSTANCE.resolveObfuscate(project, publishInfo));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
    }

    @Test
    public void obfuscatePropertyOverridesPublishInfo() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setObfuscate(true);
        project.getExtensions().getExtraProperties().set("obfuscate", "false");

        assertFalse(PublishConfigResolver.INSTANCE.resolveObfuscate(project, publishInfo));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
    }

    @Test
    public void centralPublishAttachesSourcesJarEvenWhenObfuscated() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setVersion("1.0.0");

        assertFalse(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
        assertTrue(PublishConfigResolver.INSTANCE.shouldAttachSourcesJar(project, publishInfo, "1.0.0", true));
        assertFalse(PublishConfigResolver.INSTANCE.shouldAttachSourcesJar(project, publishInfo, "1.0.0", false));
        assertTrue(PublishConfigResolver.INSTANCE.shouldAttachSourcesJar(project, publishInfo, "1.0.0-debug", false));
    }
}
