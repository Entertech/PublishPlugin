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
                "scm:git:git://github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmConnection(project, publishInfo)
        );
        assertEquals(
                "scm:git:ssh://git@github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmDeveloperConnection(project, publishInfo)
        );
    }

    @Test
    public void scmConnectionsCanBeDerivedFromSshRemoteStyleUrl() {
        Project project = ProjectBuilder.builder().build();
        PublishInfo publishInfo = new PublishInfo();
        publishInfo.setScmUrl("git@github.com:Entertech/PublishPlugin.git");

        assertEquals(
                "scm:git:git://github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmConnection(project, publishInfo)
        );
        assertEquals(
                "scm:git:ssh://git@github.com/Entertech/PublishPlugin.git",
                PublishConfigResolver.INSTANCE.resolveScmDeveloperConnection(project, publishInfo)
        );
    }
}
