package custom.android.plugin.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CentralPublishConfigTemplateWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsTemplateToExistingLocalPropertiesAndPreservesAndroidFields() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");
        Files.write(localProperties.toPath(), (
                "sdk.dir=/Users/example/Library/Android/sdk\n"
                        + "ndk.dir=/Users/example/Library/Android/ndk\n"
                        + "centralPublish.centralNamespace=cn.custom\n"
        ).getBytes(StandardCharsets.UTF_8));

        CentralPublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        String content = read(localProperties);
        assertTrue(content.contains("sdk.dir=/Users/example/Library/Android/sdk"));
        assertTrue(content.contains("ndk.dir=/Users/example/Library/Android/ndk"));
        assertTrue(content.contains("centralPublish.centralNamespace=cn.custom"));
        assertTrue(content.contains("centralPublish.githubRepo="));
        assertTrue(content.contains("centralPublish.workflowUses="));
        assertEquals(1, count(content, "centralPublish.centralNamespace="));
    }

    @Test
    public void appendsLocalPropertiesToGitignoreWhenMissing() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");
        File gitignore = new File(rootDir, ".gitignore");

        CentralPublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        assertTrue(read(localProperties).contains("centralPublish.githubRepo="));
        assertTrue(read(gitignore).contains("local.properties"));
    }

    @Test
    public void templateDocumentsEnumAndBooleanOptions() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");

        CentralPublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        String content = read(localProperties);
        assertTrue(content.contains("user_managed:"));
        assertTrue(content.contains("automatic:"));
        assertTrue(content.contains("true: 调用 gh secret set"));
        assertTrue(content.contains("false/空: 不生成"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
