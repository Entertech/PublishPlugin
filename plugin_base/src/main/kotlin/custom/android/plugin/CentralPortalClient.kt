package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.Base64
import java.util.Locale
import java.nio.file.Files
import java.util.UUID
import kotlin.math.min

object CentralPortalClient {
    fun uploadBundle(project: Project, bundle: File, publishInfo: PublishInfo): String {
        require(bundle.isFile) { "Central Portal bundle does not exist: ${bundle.path}" }
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo)
        val boundary = "----PublishPlugin${UUID.randomUUID()}"
        val publishingType = PublishConfigResolver.resolveCentralPublishingType(project, publishInfo)
            .uppercase(Locale.ROOT)
        val url = URI(
            "${PublishConfigResolver.resolveCentralPortalApiBaseUrl(project)}/upload" +
                "?publishingType=${URLEncoder.encode(publishingType, StandardCharsets.UTF_8.name())}"
        ).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Authorization", publisherAuth(credentials.username, credentials.password))
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.use { output ->
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n".toByteArray())
            output.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            Files.copy(bundle.toPath(), output)
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val statusCode = connection.responseCode
        val response = readResponse(connection)
        if (statusCode !in 200..299) throw GradleException("Central Portal bundle upload failed: HTTP $statusCode ${sanitize(response)}")
        val deploymentId = Regex("(?:deploymentId|deployment_id|id)\\\"?\\s*[:=]\\s*\\\"?([A-Za-z0-9._-]+)").find(response)?.groupValues?.get(1)
            ?: response.trim().takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: throw GradleException("Central Portal bundle upload succeeded but deployment id was missing")
        PluginLogUtil.printlnInfoInScreen("Central Portal deployment uploaded: $deploymentId")
        return deploymentId
    }

    fun deploymentStatus(project: Project, publishInfo: PublishInfo, deploymentId: String): String {
        val encodedId = URLEncoder.encode(deploymentId, StandardCharsets.UTF_8.name())
        return requestDeployment(project, publishInfo, "/status?id=$encodedId", "POST")
    }

    fun publishDeployment(project: Project, publishInfo: PublishInfo, deploymentId: String) {
        requestDeployment(project, publishInfo, "/deployment/${encodePathSegment(deploymentId)}", "POST")
    }

    fun dropDeployment(project: Project, publishInfo: PublishInfo, deploymentId: String) {
        requestDeployment(project, publishInfo, "/deployment/${encodePathSegment(deploymentId)}", "DELETE")
    }

    /**
     * Wait until Central has validated a user-managed deployment, published an automatic
     * deployment, or reported a terminal failure.
     * The raw status response is returned so callers can include the Portal details
     * in their manifest/logs without exposing credentials.
     */
    fun waitForDeployment(
        project: Project,
        publishInfo: PublishInfo,
        deploymentId: String,
        timeoutMillis: Long = 10 * 60 * 1_000L,
        pollIntervalMillis: Long = 5_000L
    ): String {
        require(timeoutMillis >= 0) { "timeoutMillis must be non-negative" }
        require(pollIntervalMillis >= 0) { "pollIntervalMillis must be non-negative" }
        val successStates = if (
            PublishConfigResolver.resolveCentralPublishingType(project, publishInfo) == "automatic"
        ) {
            setOf("PUBLISHED")
        } else {
            setOf("VALIDATED", "PUBLISHED")
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var lastResponse = ""
        while (true) {
            lastResponse = deploymentStatus(project, publishInfo, deploymentId)
            val state = deploymentState(lastResponse)
            when {
                state in successStates -> return lastResponse
                state in FAILURE_STATES -> {
                    throw GradleException(
                        "Central Portal deployment $deploymentId failed validation: ${sanitize(lastResponse)}"
                    )
                }
                System.nanoTime() >= deadline -> {
                    throw GradleException(
                        "Timed out waiting for Central Portal deployment $deploymentId: ${sanitize(lastResponse)}"
                    )
                }
            }
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            Thread.sleep(min(pollIntervalMillis, remainingMillis.coerceAtLeast(1L)))
        }
    }

    fun manualUpload(project: Project, publishInfo: PublishInfo) {
        val namespace = PublishConfigResolver.resolveCentralNamespace(project, publishInfo)
        val publishingType = PublishConfigResolver.resolveCentralPublishingType(project, publishInfo)
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo)
        val token = Base64.getEncoder().encodeToString(
            "${credentials.username}:${credentials.password}".toByteArray(StandardCharsets.UTF_8)
        )
        val encodedNamespace = URLEncoder.encode(namespace, StandardCharsets.UTF_8.name())
        val encodedPublishingType = URLEncoder.encode(publishingType, StandardCharsets.UTF_8.name())
        val url = URI(
            "${PublishConfigResolver.CENTRAL_MANUAL_UPLOAD_BASE_URL}/$encodedNamespace" +
                "?publishing_type=$encodedPublishingType"
        ).toURL()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

        val statusCode = connection.responseCode
        val response = readResponse(connection)
        if (statusCode !in 200..299) {
            throw GradleException(
                "Central Portal manual upload failed: HTTP $statusCode ${sanitize(response)}"
            )
        }

        if (response.isNotBlank()) {
            PluginLogUtil.printlnInfoInScreen("Central Portal manual upload response: ${sanitize(response)}")
        }
        PluginLogUtil.printlnInfoInScreen("Central Portal deployments: https://central.sonatype.com/publishing/deployments")
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun requestDeployment(
        project: Project,
        publishInfo: PublishInfo,
        path: String,
        method: String
    ): String {
        val credentials = PublishConfigResolver.resolveCentralCredentials(project, publishInfo)
        val connection = (URI(PublishConfigResolver.resolveCentralPortalApiBaseUrl(project) + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", publisherAuth(credentials.username, credentials.password))
        }
        val statusCode = connection.responseCode
        val response = readResponse(connection)
        if (statusCode !in 200..299) throw GradleException("Central Portal deployment request failed: HTTP $statusCode ${sanitize(response)}")
        return response
    }

    private fun publisherAuth(username: String, password: String): String {
        return "Bearer " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun deploymentState(response: String): String {
        return Regex("(?i)(?:deploymentState|state|status)\\\"?\\s*[:=]\\s*\\\"?([A-Za-z_-]+)")
            .find(response)
            ?.groupValues
            ?.get(1)
            ?.uppercase(Locale.ROOT)
            .orEmpty()
    }

    private val FAILURE_STATES = setOf("FAILED", "REJECTED", "DROPPED", "ERROR")

    private fun sanitize(value: String): String {
        return value.replace(Regex("(?i)(password|token|secret)[^\\s,}]*"), "$1=***")
    }
}
