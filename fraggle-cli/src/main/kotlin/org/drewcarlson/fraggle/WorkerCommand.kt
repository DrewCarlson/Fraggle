package org.drewcarlson.fraggle

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.drewcarlson.fraggle.executor.LocalToolExecutor
import org.drewcarlson.fraggle.executor.RemoteToolRequest
import org.drewcarlson.fraggle.executor.RemoteToolResponse
import org.drewcarlson.fraggle.executor.supervision.NoOpToolSupervisor
import org.drewcarlson.fraggle.tools.DefaultTools
import org.drewcarlson.fraggle.tools.scheduling.TaskScheduler
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

        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val toolExecutor = LocalToolExecutor(workDirPath)
        val supervisor = NoOpToolSupervisor()
        val taskScheduler = TaskScheduler(taskScope, {})

        // Create tool registry without remote client (we ARE the remote)
        val toolRegistry = DefaultTools.createToolRegistry(
            toolExecutor = toolExecutor,
            httpClient = HttpClient(),
            taskScheduler = taskScheduler,
            supervisor = supervisor,
            remoteClient = null,
            playwrightFetcher = null,
        )

        val toolMap = toolRegistry.tools.associateBy { it.name }

        logger.info("Worker loaded ${toolMap.size} tools: ${toolMap.keys}")

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
                    val toolNames = toolMap.keys.toList()
                    call.respondText(
                        json.encodeToString(toolNames),
                        ContentType.Application.Json,
                    )
                }

                post("/api/v1/tools/execute") {
                    try {
                        val request = call.receive<RemoteToolRequest>()
                        val tool = toolMap[request.toolName]
                        if (tool == null) {
                            val response = RemoteToolResponse(error = "Unknown tool: ${request.toolName}")
                            call.respond(
                                HttpStatusCode.NotFound,
                                response
                            )
                            return@post
                        }

                        // Deserialize args using the tool's serializer and execute
                        val argsElement = json.parseToJsonElement(request.argsJson) as JsonObject
                        @Suppress("UNCHECKED_CAST")
                        val typedTool = tool as ai.koog.agents.core.tools.SimpleTool<Any>
                        val args = json.decodeFromJsonElement(typedTool.argsSerializer, argsElement)
                        val result = withContext(Dispatchers.Default) {
                            typedTool.execute(args)
                        }

                        val response = RemoteToolResponse(result = result)
                        call.respond(response)
                    } catch (e: Exception) {
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
        server.start(wait = true)
        Unit
    }
}
