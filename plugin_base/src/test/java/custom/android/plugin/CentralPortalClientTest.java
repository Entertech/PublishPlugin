package custom.android.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CentralPortalClientTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void uploadUsesBearerTokenMultipartAndParsesDeploymentId() throws Exception {
        List<String> observations = new ArrayList<>();
        start(exchange -> {
            observations.add(exchange.getRequestMethod());
            observations.add(exchange.getRequestURI().toString());
            observations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            observations.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            observations.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"deploymentId\":\"deployment-123\"}");
        });
        Project project = project();
        PublishInfo info = new PublishInfo();
        info.setCentralPublishingType("user_managed");
        File bundle = temporaryFolder.newFile("bundle.zip");
        Files.write(bundle.toPath(), "zip-content".getBytes(StandardCharsets.UTF_8));

        String deploymentId = CentralPortalClient.INSTANCE.uploadBundle(project, bundle, info);

        assertEquals("deployment-123", deploymentId);
        assertEquals("POST", observations.get(0));
        assertTrue(observations.get(1).contains("/upload?publishingType=USER_MANAGED"));
        String expected = "Bearer " + Base64.getEncoder().encodeToString(
                "portal-user:portal-password".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(expected, observations.get(2));
        assertTrue(observations.get(3).startsWith("multipart/form-data; boundary="));
        assertTrue(observations.get(4).contains("name=\"bundle\""));
        assertTrue(observations.get(4).contains("Content-Type: application/octet-stream"));
        assertTrue(observations.get(4).contains("zip-content"));
    }

    @Test
    public void deploymentLifecycleUsesDocumentedMethodsAndPaths() throws Exception {
        List<String> requests = new ArrayList<>();
        start(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 200, "{\"deploymentState\":\"VALIDATED\"}");
        });
        Project project = project();
        PublishInfo info = new PublishInfo();

        String status = CentralPortalClient.INSTANCE.deploymentStatus(project, info, "deployment 1");
        CentralPortalClient.INSTANCE.publishDeployment(project, info, "deployment 1");
        CentralPortalClient.INSTANCE.dropDeployment(project, info, "deployment 1");

        assertTrue(status.contains("VALIDATED"));
        assertEquals("POST /api/v1/publisher/status?id=deployment+1", requests.get(0));
        assertEquals("POST /api/v1/publisher/deployment/deployment%201", requests.get(1));
        assertEquals("DELETE /api/v1/publisher/deployment/deployment%201", requests.get(2));
    }

    @Test
    public void waitForDeploymentPollsUntilValidated() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            String state = calls.incrementAndGet() == 1 ? "PENDING" : "VALIDATED";
            respond(exchange, 200, "{\"deploymentState\":\"" + state + "\"}");
        });

        String response = CentralPortalClient.INSTANCE.waitForDeployment(
                project(), new PublishInfo(), "deployment-1", 1_000, 1
        );

        assertTrue(response.contains("VALIDATED"));
        assertEquals(2, calls.get());
    }

    @Test
    public void automaticDeploymentWaitsUntilPublished() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            String state = calls.incrementAndGet() == 1 ? "VALIDATED" : "PUBLISHED";
            respond(exchange, 200, "{\"deploymentState\":\"" + state + "\"}");
        });
        PublishInfo info = new PublishInfo();
        info.setCentralPublishingType("automatic");

        String response = CentralPortalClient.INSTANCE.waitForDeployment(
                project(), info, "deployment-automatic", 1_000, 1
        );

        assertTrue(response.contains("PUBLISHED"));
        assertEquals(2, calls.get());
    }

    @Test
    public void waitForDeploymentReportsTimeout() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"deploymentState\":\"PENDING\"}"));

        try {
            CentralPortalClient.INSTANCE.waitForDeployment(
                    project(), new PublishInfo(), "deployment-timeout", 1, 0
            );
            fail("Expected timeout");
        } catch (GradleException error) {
            assertTrue(error.getMessage().contains("Timed out waiting"));
            assertTrue(error.getMessage().contains("deployment-timeout"));
        }
    }

    @Test
    public void waitForDeploymentStopsOnTerminalFailure() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"deploymentState\":\"FAILED\",\"token\":\"secret-value\"}"));

        try {
            CentralPortalClient.INSTANCE.waitForDeployment(project(), new PublishInfo(), "deployment-failed", 1_000, 1);
            fail("Expected terminal failure");
        } catch (GradleException error) {
            assertTrue(error.getMessage().contains("failed validation"));
            assertFalse(error.getMessage().contains("secret-value"));
        }
    }

    @Test
    public void unknownDeploymentStateTimesOutInsteadOfPassing() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"deploymentState\":\"ALIEN_STATE\"}"));

        try {
            CentralPortalClient.INSTANCE.waitForDeployment(project(), new PublishInfo(), "deployment-unknown", 1, 0);
            fail("Expected unknown state timeout");
        } catch (GradleException error) {
            assertTrue(error.getMessage().contains("Timed out waiting"));
            assertTrue(error.getMessage().contains("ALIEN_STATE"));
        }
    }

    @Test
    public void errorResponseIsSanitized() throws Exception {
        start(exchange -> respond(
                exchange,
                401,
                "{\"token\":\"secret-token-value\",\"password\":\"secret-password-value\"}"
        ));
        File bundle = temporaryFolder.newFile("bundle.zip");

        try {
            CentralPortalClient.INSTANCE.uploadBundle(project(), bundle, new PublishInfo());
            fail("Expected upload failure");
        } catch (GradleException error) {
            assertTrue(error.getMessage().contains("HTTP 401"));
            assertFalse(error.getMessage().contains("secret-token-value"));
            assertFalse(error.getMessage().contains("secret-password-value"));
        }
    }

    private Project project() {
        Project project = ProjectBuilder.builder().build();
        project.getExtensions().getExtraProperties().set("centralPortalApiBaseUrl", baseUrl());
        project.getExtensions().getExtraProperties().set("centralUsername", "portal-user");
        project.getExtensions().getExtraProperties().set("centralPassword", "portal-password");
        return project;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/publisher";
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
