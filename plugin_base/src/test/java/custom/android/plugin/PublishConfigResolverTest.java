package custom.android.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
