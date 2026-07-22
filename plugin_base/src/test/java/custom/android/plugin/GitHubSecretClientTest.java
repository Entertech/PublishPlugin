package custom.android.plugin;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitHubSecretClientTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesSecretValueThroughStdinNotCommandArguments() throws Exception {
        File fakeGh = fakeGhScript("");
        GitHubSecretClient client = new GitHubSecretClient(fakeGh.getAbsolutePath());

        client.setSecret("OWNER/REPO", "MAVEN_CENTRAL_PASSWORD", "super-secret-password");

        String args = read(new File(temporaryFolder.getRoot(), "gh-args.txt"));
        String stdin = read(new File(temporaryFolder.getRoot(), "gh-stdin.txt"));
        assertTrue(args.contains("secret set MAVEN_CENTRAL_PASSWORD -R OWNER/REPO"));
        assertFalse(args.contains("super-secret-password"));
        assertEquals("super-secret-password", stdin);
    }

    @Test
    public void listsSecretNames() throws Exception {
        File fakeGh = fakeGhScript("GPG_KEY_CONTENTS\nSIGNING_PASSWORD\n");
        GitHubSecretClient client = new GitHubSecretClient(fakeGh.getAbsolutePath());

        assertTrue(client.listSecretNames("OWNER/REPO").contains("GPG_KEY_CONTENTS"));
        assertTrue(client.listSecretNames("OWNER/REPO").contains("SIGNING_PASSWORD"));
    }

    private File fakeGhScript(String listOutput) throws Exception {
        File script = temporaryFolder.newFile("fake-gh.sh");
        String root = temporaryFolder.getRoot().getAbsolutePath();
        Files.write(script.toPath(), ("#!/bin/sh\n"
                + "echo \"$@\" >> \"" + root + "/gh-args.txt\"\n"
                + "cat > \"" + root + "/gh-stdin.txt\"\n"
                + "if [ \"$1\" = \"secret\" ] && [ \"$2\" = \"list\" ]; then\n"
                + "  printf '" + listOutput.replace("\n", "\\n") + "'\n"
                + "fi\n"
                + "exit 0\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(script.setExecutable(true));
        return script;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
