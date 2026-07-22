package custom.android.plugin.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GitUrlNormalizerTest {
    @Test
    public void normalizesCommonGitRemoteUrls() {
        assertEquals(
                "https://github.com/Entertech/demo",
                GitUrlNormalizer.INSTANCE.toHttpsRepositoryUrl("git@github.com:Entertech/demo.git")
        );
        assertEquals(
                "https://github.com/Entertech/demo",
                GitUrlNormalizer.INSTANCE.toHttpsRepositoryUrl("ssh://git@github.com/Entertech/demo.git")
        );
        assertEquals(
                "https://github.com/Entertech/demo",
                GitUrlNormalizer.INSTANCE.toHttpsRepositoryUrl("https://github.com/Entertech/demo.git")
        );
    }

    @Test
    public void derivesScmConnectionsFromHttpsUrl() {
        assertEquals(
                "scm:git:https://github.com/Entertech/demo.git",
                GitUrlNormalizer.INSTANCE.toScmConnection("https://github.com/Entertech/demo")
        );
        assertEquals(
                "scm:git:ssh://git@github.com/Entertech/demo.git",
                GitUrlNormalizer.INSTANCE.toScmDeveloperConnection("https://github.com/Entertech/demo")
        );
    }
}
