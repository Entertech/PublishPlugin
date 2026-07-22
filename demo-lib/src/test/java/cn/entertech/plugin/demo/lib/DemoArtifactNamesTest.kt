package cn.entertech.plugin.demo.lib

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoArtifactNamesTest {
    @Test
    fun artifactIdAddsProductPrefixAndAuthenticationSuffix() {
        assertEquals(
            "breath-publish-demo-lib-authentication",
            DemoArtifactNames.artifactId(project = "breath", authentication = "auth")
        )
        assertEquals(
            "sdk-publish-demo-lib",
            DemoArtifactNames.artifactId(project = "sdk", authentication = "noAuth")
        )
    }
}
