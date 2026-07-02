package custom.android.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object CentralPortalClient {
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

    private fun sanitize(value: String): String {
        return value.replace(Regex("(?i)(password|token|secret)[^\\s,}]*"), "$1=***")
    }
}
