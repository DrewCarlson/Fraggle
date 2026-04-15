package fraggle.executor

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

private val json = Json { ignoreUnknownKeys = true }

/**
 * HTTP client that forwards tool execution to a remote worker process.
 */
class RemoteToolClient(
    private val httpClient: HttpClient,
    private val workerUrl: String,
) {
    suspend fun execute(toolName: String, argsJson: String): String {
        return try {
            val request = RemoteToolRequest(toolName = toolName, argsJson = argsJson)
            val response = httpClient.post("$workerUrl/api/v1/tools/execute") {
                contentType(ContentType.Application.Json)
                setBody(request)
                // Tool execution can take much longer than the default 30s request timeout
                // (e.g. shell commands, large file operations, web scraping)
                timeout {
                    requestTimeoutMillis = 5.minutes.inWholeMilliseconds
                    socketTimeoutMillis = 5.minutes.inWholeMilliseconds
                }
            }
            val body = response.bodyAsText()
            val result = json.decodeFromString(RemoteToolResponse.serializer(), body)
            result.result ?: "Error: ${result.error ?: "Unknown remote error"}"
        } catch (e: Exception) {
            "Error: Remote tool execution failed: ${e.message}"
        }
    }
}

@Serializable
data class RemoteToolRequest(
    val toolName: String,
    val argsJson: String,
)

@Serializable
data class RemoteToolResponse(
    val result: String? = null,
    val error: String? = null,
)
