package custom.android.plugin.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PublishConfigLoaderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsOnlyPublishKeysAndIgnoresBlankValues() throws Exception {
        File configFile = temporaryFolder.newFile("local.properties");
        Files.write(configFile.toPath(), (
                "sdk.dir=/Users/example/Library/Android/sdk\n"
                        + "centralNamespace=wrong\n"
                        + "centralPublish.centralNamespace=legacy\n"
                        + "publish.publishTarget=githubPackages\n"
                        + "publish.githubPackagesRepository=Entertech/demo-lib\n"
                        + "publish.centralNamespace=cn.entertech\n"
                        + "publish.centralPublishingType=\n"
                        + "publish.centralUsername=legacy-user\n"
                        + "publish.centralPassword=legacy-password\n"
        ).getBytes(StandardCharsets.UTF_8));

        PublishConfig config = PublishConfigLoader.INSTANCE.load(configFile);

        assertEquals("github_packages", config.getPublishTarget());
        assertEquals("Entertech/demo-lib", config.getGithubPackagesRepository());
        assertEquals("cn.entertech", config.getCentralNamespace());
        assertEquals("", config.getCentralPublishingType());
        assertEquals("legacy-user", config.getMavenCentralUsername());
        assertEquals("legacy-password", config.getMavenCentralPassword());
    }

    @Test
    public void readsLegacyCentralPublishKeys() throws Exception {
        File configFile = temporaryFolder.newFile("legacy-local.properties");
        Files.write(configFile.toPath(), (
                "centralPublish.publishTarget=central\n"
                        + "centralPublish.githubRepo=Entertech/demo-lib\n"
                        + "centralPublish.workflowUses=Entertech/PublishPlugin/.github/workflows/central-publish.yml@main\n"
        ).getBytes(StandardCharsets.UTF_8));

        PublishConfig config = PublishConfigLoader.INSTANCE.load(configFile);

        assertEquals("central", config.getPublishTarget());
        assertEquals("Entertech/demo-lib", config.getGithubRepo());
        assertEquals(
                "Entertech/PublishPlugin/.github/workflows/publish.yml@main",
                config.getWorkflowUses()
        );
    }

    @Test
    public void rejectsModuleAndComponentFields() throws Exception {
        File modulesFile = temporaryFolder.newFile("modules.properties");
        Files.write(modulesFile.toPath(), "publish.modules=:demo-lib\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException modulesError = assertThrows(
                IllegalArgumentException.class,
                () -> PublishConfigLoader.INSTANCE.load(modulesFile)
        );
        assertEquals(true, modulesError.getMessage().contains("modules"));

        File componentFile = temporaryFolder.newFile("component.properties");
        Files.write(componentFile.toPath(), "publish.pomUrl=https://example.com\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException componentError = assertThrows(
                IllegalArgumentException.class,
                () -> PublishConfigLoader.INSTANCE.load(componentFile)
        );
        assertEquals(true, componentError.getMessage().contains("PublishInfo"));
    }

    @Test
    public void validatesPublishingType() throws Exception {
        File configFile = temporaryFolder.newFile("publishing-type.properties");
        Files.write(configFile.toPath(), "publish.centralPublishingType=portal_api\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PublishConfigLoader.INSTANCE.load(configFile)
        );

        assertEquals(true, error.getMessage().contains("user_managed"));
        assertEquals(true, error.getMessage().contains("automatic"));
    }

    @Test
    public void validatesPublishTarget() throws Exception {
        File configFile = temporaryFolder.newFile("publish-target.properties");
        Files.write(configFile.toPath(), "publish.publishTarget=mavenCentral\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PublishConfigLoader.INSTANCE.load(configFile)
        );

        assertEquals(true, error.getMessage().contains("github_packages"));
        assertEquals(true, error.getMessage().contains("central"));
    }
}
