package fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import fraggle.agent.tool.AgentToolDef
import fraggle.executor.LocalToolExecutor
import fraggle.executor.RemoteToolRequest
import fraggle.executor.RemoteToolResponse
import fraggle.tools.DefaultTools
import org.slf4j.LoggerFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Lightweight worker process for remote tool execution.
 *
 * Starts an HTTP server that accepts tool execution requests and returns results.
 * Does not load the full DI graph — only creates the tool registry directly.
 */
class WorkerCommand : CliktCommand(name = "worker") {
    private val logger = LoggerFactory.getLogger(WorkerCommand::class.java)

    private val port by option("-p", "--port", help = "Port to listen on").int().default(9292)
    private val workDir by option("-w", "--work-dir", help = "Working directory for tools").default("./data/workspace")

    private val json = Json { ignoreUnknownKeys = true }

    override fun run() = runBlocking {
        logger.info("Starting Fraggle worker on port $port")

        val workDirPath = FraggleEnvironment.resolvePath(workDir)
        if (!workDirPath.exists()) {
            workDirPath.createDirectories()
        }

        val toolExecutor = LocalToolExecutor(workDirPath)

        // Create tool registry (worker is the remote endpoint — no forwarding).
        // Scheduling tools live in fraggle-assistant and are not exposed by the
        // worker; the remote endpoint serves only the generic file/shell/web tools.
        val toolRegistry = DefaultTools.createToolRegistry(
            toolExecutor = toolExecutor,
            httpClient = HttpClient(),
            playwrightFetcher = null,
        )
        val toolKeys = toolRegistry.tools.map { it.name }
        logger.info("Worker loaded ${toolRegistry.tools.size} tools: $toolKeys")

        val server = embeddedServer(Netty, port = port) {
            install(AutoHeadResponse)
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(CallId) {
                header(HttpHeaders.XRequestId)
                generate { java.util.UUID.randomUUID().toString() }
            }
            install(CallLogging) {
                callIdMdc("call-id")
            }
            routing {
                get("/health") {
                    call.respondText(
                        json.encodeToString(toolKeys),
                        ContentType.Application.Json,
                    )
                }

                post("/api/v1/tools/execute") {
                    try {
                        val request = call.receive<RemoteToolRequest>()
                        val tool = toolRegistry.findTool(request.toolName)
                        if (tool == null) {
                            val response = RemoteToolResponse(error = "Unknown tool: ${request.toolName}")
                            call.respond(
                                HttpStatusCode.NotFound,
                                response
                            )
                            return@post
                        }

                        @Suppress("UNCHECKED_CAST")
                        val args = json.decodeFromString(
                            tool.argsSerializer as KSerializer<Any>,
                            request.argsJson,
                        )
                        @Suppress("UNCHECKED_CAST")
                        val result = withContext(Dispatchers.Default) {
                            (tool as AgentToolDef<Any>).execute(args)
                        }

                        val response = RemoteToolResponse(result = result)
                        call.respond(response)
                    } catch (e: Throwable) {
                        logger.error("Tool execution error: ${e.message}", e)
                        val response = RemoteToolResponse(error = e.message ?: "Unknown error")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            response
                        )
                    }
                }
            }
        }

        logger.info("Fraggle worker running on port $port")
        server.startSuspend(wait = true)
        Unit
    }
}
