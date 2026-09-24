package custom.android.plugin

import com.sun.net.httpserver.HttpServer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.jar.JarOutputStream

class PublishInfrastructureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `preflight distinguishes missing existing authorization and retryable responses`() {
        val authorization = "Basic " + Base64.getEncoder()
            .encodeToString("user:password".toByteArray(StandardCharsets.UTF_8))
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/missing/com/example/lib/1.0/lib-1.0.pom") { exchange ->
            assertEquals(authorization, exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/existing/com/example/lib/1.0/lib-1.0.pom") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/unavailable/com/example/lib/1.0/lib-1.0.pom") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val publication = listOf(PublishValidationPublication("main", "com.example", "lib", "1.0"))
            val credentials = PublishConfigResolver.RepositoryCredentials("user", "password")
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals("passed", PublishPreflight.checkMavenRepository("github_packages", "$base/missing", credentials, publication, false).status)
            assertEquals("failed", PublishPreflight.checkMavenRepository("github_packages", "$base/existing", credentials, publication, false).status)
            assertEquals("passed", PublishPreflight.checkMavenRepository("github_packages", "$base/existing", credentials, publication, true).status)
            val unavailable = PublishPreflight.checkMavenRepository("github_packages", "$base/unavailable", credentials, publication, false)
            assertEquals("failed", unavailable.status)
            assertTrue(unavailable.retryable)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `execution state persists exact fingerprint and successful provider`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("state-project")).build()
        val store = PublishExecutionStateStore(project)
        val publication = PublishValidationPublication("main", "com.example", "lib", "1.0")
        val fingerprint = PublishExecutionStateStore.fingerprint(listOf(publication), null)
        store.write(mapOf("github_packages" to PublishProviderState("github_packages", "succeeded", fingerprint)))

        val restored = store.read().getValue("github_packages")
        assertEquals("succeeded", restored.status)
        assertEquals(fingerprint, restored.fingerprint)
        assertTrue(project.file("build/reports/publish/provider-state.json").readText().contains("\"providers\""))
    }

    @Test
    fun `preflight skips provider already completed by resume`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("resume-project")).build()
        val results = PublishPreflight.run(
            project,
            PublishInfo(),
            ExplicitPublishTarget.GITHUB_PACKAGES,
            listOf(PublishValidationPublication("main", "com.example", "lib", "1.0")),
            skipProviders = setOf("github_packages")
        )

        assertEquals(1, results.size)
        assertEquals("github_packages", results.single().provider)
        assertEquals("skipped", results.single().status)
    }

    @Test
    fun `project scanner classifies Maven layout and supply chain writes evidence`() {
        val projectDir = temporaryFolder.newFolder("bundle-project")
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val root = project.file("build/reports/publish/project-bundle")
        val versionDir = root.resolve("com/example/lib/1.0").apply { mkdirs() }
        JarOutputStream(versionDir.resolve("lib-1.0.jar").outputStream()).use { }
        versionDir.resolve("lib-1.0.pom").writeText("<project><dependencies/></project>")
        versionDir.resolve("lib-1.0-sources.jar").writeText("sources")
        versionDir.resolve("lib-1.0-javadoc.jar").writeText("javadoc")
        versionDir.resolve("lib-1.0.jar.asc").writeText("signature")
        val coordinate = PublishValidationPublication("main", "com.example", "lib", "1.0")
        val publication = ProjectArtifactBundleProducer.scanPublication(root, coordinate)
        val bundle = PreparedArtifactBundle(1, root, listOf(publication))
        ArtifactBundleValidator.validate(bundle, requireCentral = true)

        val evidence = PublishSupplyChain.collect(project, bundle)
        assertTrue(project.file("build/reports/publish/publish-sbom.cdx.json").isFile)
        assertTrue(evidence.provenance.getValue("artifactBundleSha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(evidence.gates.any { it.name == "dependency_policy" && it.status == "passed" })
    }

    @Test
    fun `named variants publish only the requested release combinations`() {
        val info = PublishInfo().apply {
            publishVariants("sdkAuthRelease", "breathNoAuthRelease")
        }
        val candidates = listOf(
            variant("sdkAuthRelease", "project" to "sdk", "authentication" to "auth"),
            variant("sdkNoAuthRelease", "project" to "sdk", "authentication" to "noAuth"),
            variant("breathAuthRelease", "project" to "breath", "authentication" to "auth"),
            variant("breathNoAuthRelease", "project" to "breath", "authentication" to "noAuth")
        )

        val selected = candidates.filter { info.shouldPublishVariant(it) }.map { it.name }

        assertEquals(listOf("sdkAuthRelease", "breathNoAuthRelease"), selected)
        assertTrue(info.hasExplicitVariantSelection())
        assertFalse(info.publishAllVariantsEnabled())
    }

    @Test
    fun `publish all variants keeps every candidate that survives filters`() {
        val info = PublishInfo().apply {
            publishAllVariants()
            skipVariantIf { variant -> variant.flavor("authentication") == "noAuth" }
        }
        val candidates = listOf(
            variant("sdkAuthRelease", "project" to "sdk", "authentication" to "auth"),
            variant("sdkNoAuthRelease", "project" to "sdk", "authentication" to "noAuth"),
            variant("breathAuthRelease", "project" to "breath", "authentication" to "auth")
        )

        val selected = candidates.filter { info.shouldPublishVariant(it) }.map { it.name }

        assertEquals(listOf("sdkAuthRelease", "breathAuthRelease"), selected)
        assertTrue(info.publishAllVariantsEnabled())
        assertTrue(info.hasExplicitVariantSelection())
    }

    @Test
    fun `activating a variant does not expand the configured allow list`() {
        val info = PublishInfo().apply {
            publishVariants("breathAuthRelease")
        }

        try {
            info.activatePublishVariant("sdkAuthRelease")
            throw AssertionError("variant outside publishVariants must fail")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("sdkAuthRelease"))
        }
        assertEquals(setOf("breathAuthRelease"), info.publishVariantNames())
        assertTrue(info.shouldPublishVariant(variant("breathAuthRelease")))
    }

    @Test
    fun `active variant publishes only that candidate`() {
        val info = PublishInfo().apply {
            publishAllVariants()
            activatePublishVariant("sdkAuthRelease")
        }
        val candidates = listOf(
            variant("sdkAuthRelease"),
            variant("breathAuthRelease")
        )

        assertEquals(listOf("sdkAuthRelease"), candidates.filter { info.shouldPublishVariant(it) }.map { it.name })
        assertEquals("sdkAuthRelease", info.activePublishVariantName())
    }

    @Test
    fun `variant task names do not depend on the first character of the variant`() {
        assertEquals(
            "PublishLibraryFlowtimeNoAuthReleaseLocalTask",
            PublishTaskNames.variantLocal(PublishComponentKind.LIBRARY, "flowtimeNoAuthRelease")
        )
        assertTrue(PublishTaskNames.isVariantLocalTask("PublishLibraryFlowtimeNoAuthReleaseLocalTask"))
        assertTrue(PublishTaskNames.isVariantLocalTask("PublishLibrary2faReleaseLocalTask"))
        assertFalse(PublishTaskNames.isVariantLocalTask("PublishLibraryLocalTask"))
    }

    @Test
    fun `default variant selection stays inactive until explicitly configured`() {
        val info = PublishInfo()

        assertFalse(info.hasExplicitVariantSelection())
        assertTrue(info.publishVariantNames().isEmpty())
        assertTrue(info.shouldPublishVariant(variant("release")))
    }

    private fun variant(name: String, vararg flavors: Pair<String, String>): PublishVariantInfo {
        return PublishVariantInfo(
            name = name,
            buildType = "release",
            flavors = flavors.toMap()
        )
    }

    @Test
    fun `all requires Central bundle roles only when Central provider is enabled`() {
        assertTrue(requiresCentralBundle(ExplicitPublishTarget.CENTRAL, centralEnabled = false))
        assertTrue(requiresCentralBundle(ExplicitPublishTarget.ALL, centralEnabled = true))
        assertEquals(false, requiresCentralBundle(ExplicitPublishTarget.ALL, centralEnabled = false))
        assertEquals(false, requiresCentralBundle(ExplicitPublishTarget.GITHUB_PACKAGES, centralEnabled = true))
    }
}
