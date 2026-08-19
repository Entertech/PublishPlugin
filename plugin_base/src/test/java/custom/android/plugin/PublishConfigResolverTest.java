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
    public void hasSourceDefaultsToFalseAndSkipsRealSourcesForReleaseVersions() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setVersion("1.0.0");

        assertFalse(PublishConfigResolver.INSTANCE.resolveHasSource(project, publishInfo));
        assertFalse(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0-debug"));
    }

    @Test
    public void hasSourceTruePublishesSourcesForReleaseVersions() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setHasSource(true);

        assertTrue(PublishConfigResolver.INSTANCE.resolveHasSource(project, publishInfo));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
    }

    @Test
    public void hasSourcePropertyOverridesPublishInfo() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setHasSource(false);
        project.getExtensions().getExtraProperties().set("hasSource", "true");

        assertTrue(PublishConfigResolver.INSTANCE.resolveHasSource(project, publishInfo));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
    }

    @Test
    public void obfuscateFalseStillPublishesSourcesForCompatibility() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setObfuscate(false);

        assertTrue(PublishConfigResolver.INSTANCE.resolveHasSource(project, publishInfo));
        assertTrue(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));

        project.getExtensions().getExtraProperties().set("obfuscate", "true");
        assertFalse(PublishConfigResolver.INSTANCE.resolveHasSource(project, publishInfo));
        assertFalse(PublishConfigResolver.INSTANCE.shouldPublishSources(project, publishInfo, "1.0.0"));
    }

}
