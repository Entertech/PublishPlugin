package custom.android.plugin.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PublishConfigTemplateWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsTemplateToExistingLocalPropertiesAndPreservesAndroidFields() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");
        Files.write(localProperties.toPath(), (
                "sdk.dir=/Users/example/Library/Android/sdk\n"
                        + "ndk.dir=/Users/example/Library/Android/ndk\n"
                        + "publish.centralNamespace=cn.custom\n"
        ).getBytes(StandardCharsets.UTF_8));

        PublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        String content = read(localProperties);
        assertTrue(content.contains("sdk.dir=/Users/example/Library/Android/sdk"));
        assertTrue(content.contains("ndk.dir=/Users/example/Library/Android/ndk"));
        assertTrue(content.contains("publish.centralNamespace=cn.custom"));
        assertTrue(content.contains("publish.publishTarget="));
        assertTrue(content.contains("publish.githubRepo="));
        assertTrue(content.contains("publish.workflowUses="));
        assertEquals(1, count(content, "publish.centralNamespace="));
    }

    @Test
    public void appendsLocalPropertiesToGitignoreWhenMissing() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");
        File gitignore = new File(rootDir, ".gitignore");

        PublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        assertTrue(read(localProperties).contains("publish.githubRepo="));
        assertTrue(read(gitignore).contains("local.properties"));
    }

    @Test
    public void templateDocumentsEnumAndBooleanOptions() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");

        PublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        String content = read(localProperties);
        assertTrue(content.contains("user_managed:"));
        assertTrue(content.contains("automatic:"));
        assertTrue(content.contains("true: Call gh secret set"));
        assertTrue(content.contains("Supported values: github_packages, central, all"));
        assertTrue(content.contains("false/blank: Do not generate"));
    }

    @Test
    public void generatedTemplateCommentsAreAsciiSafeForPropertiesEditors() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");

        PublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, false);

        byte[] bytes = Files.readAllBytes(localProperties.toPath());
        for (byte value : bytes) {
            assertTrue("Generated template contains non-ASCII byte: " + (value & 0xff), (value & 0x80) == 0);
        }
        String utf8Content = new String(bytes, StandardCharsets.UTF_8);
        String latin1Content = new String(bytes, StandardCharsets.ISO_8859_1);
        assertEquals(utf8Content, latin1Content);
        assertTrue(utf8Content.contains("# GitHub repository in owner/repo format."));
    }

    @Test
    public void overwriteRewritesTemplateCommentsAndPreservesExistingValues() throws Exception {
        File rootDir = temporaryFolder.newFolder("project");
        File localProperties = new File(rootDir, "local.properties");
        Files.write(localProperties.toPath(), (
                "# Android local SDK path\n"
                        + "sdk.dir=/Users/example/Library/Android/sdk\n\n"
                        + "# Old generated GitHub repository comment\n"
                        + "centralPublish.githubRepo=Entertech/demo-lib\n\n"
                        + "# Old generated GitHub secrets comment\n"
                        + "centralPublish.githubSecrets=true\n"
                        + "centralPublish.workflowUses=Entertech/PublishPlugin/.github/workflows/central-publish.yml@main\n"
        ).getBytes(StandardCharsets.UTF_8));

        PublishConfigTemplateWriter.INSTANCE.writeTemplate(rootDir, localProperties, true);

        String content = read(localProperties);
        assertTrue(content.contains("# Android local SDK path\nsdk.dir=/Users/example/Library/Android/sdk"));
        assertTrue(content.contains("# GitHub repository in owner/repo format."));
        assertTrue(content.contains("publish.githubRepo=Entertech/demo-lib"));
        assertTrue(content.contains("publish.githubSecrets=true"));
        assertTrue(content.contains("publish.workflowUses=Entertech/PublishPlugin/.github/workflows/publish.yml@main"));
        assertEquals(false, content.contains("centralPublish.githubRepo"));
        assertEquals(1, count(content, "publish.githubRepo="));
        assertEquals(1, count(content, "publish.githubSecrets="));
        assertEquals(false, content.contains("Old generated"));
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
