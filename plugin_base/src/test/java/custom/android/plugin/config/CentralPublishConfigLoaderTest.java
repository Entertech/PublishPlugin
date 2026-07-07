package custom.android.plugin.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CentralPublishConfigLoaderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsOnlyCentralPublishKeysAndIgnoresBlankValues() throws Exception {
        File configFile = temporaryFolder.newFile("local.properties");
        Files.write(configFile.toPath(), (
                "sdk.dir=/Users/example/Library/Android/sdk\n"
                        + "centralNamespace=wrong\n"
                        + "centralPublish.centralNamespace=cn.entertech\n"
                        + "centralPublish.centralPublishingType=\n"
                        + "centralPublish.centralUsername=legacy-user\n"
                        + "centralPublish.centralPassword=legacy-password\n"
        ).getBytes(StandardCharsets.UTF_8));

        CentralPublishConfig config = CentralPublishConfigLoader.INSTANCE.load(configFile);

        assertEquals("cn.entertech", config.getCentralNamespace());
        assertEquals("", config.getCentralPublishingType());
        assertEquals("legacy-user", config.getMavenCentralUsername());
        assertEquals("legacy-password", config.getMavenCentralPassword());
    }

    @Test
    public void rejectsModuleAndComponentFields() throws Exception {
        File modulesFile = temporaryFolder.newFile("modules.properties");
        Files.write(modulesFile.toPath(), "centralPublish.modules=:demo-lib\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException modulesError = assertThrows(
                IllegalArgumentException.class,
                () -> CentralPublishConfigLoader.INSTANCE.load(modulesFile)
        );
        assertEquals(true, modulesError.getMessage().contains("modules"));

        File componentFile = temporaryFolder.newFile("component.properties");
        Files.write(componentFile.toPath(), "centralPublish.pomUrl=https://example.com\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException componentError = assertThrows(
                IllegalArgumentException.class,
                () -> CentralPublishConfigLoader.INSTANCE.load(componentFile)
        );
        assertEquals(true, componentError.getMessage().contains("PublishInfo"));
    }

    @Test
    public void validatesPublishingType() throws Exception {
        File configFile = temporaryFolder.newFile("publishing-type.properties");
        Files.write(configFile.toPath(), "centralPublish.centralPublishingType=portal_api\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CentralPublishConfigLoader.INSTANCE.load(configFile)
        );

        assertEquals(true, error.getMessage().contains("user_managed"));
        assertEquals(true, error.getMessage().contains("automatic"));
    }
}
