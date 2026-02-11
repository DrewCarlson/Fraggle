package fraggle.backend.routes

import fraggle.api.*
import fraggle.memory.Memory
import fraggle.memory.MemoryScope
import fraggle.models.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ApiRoutesTest {

    private val json = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val services = mockk<FraggleServices>(relaxed = true) {
        // Nullable properties / return-null-by-default lookups
        every { discordOAuth } returns null
        every { bridges.getBridge(any()) } returns null
        every { scheduler.getTask(any()) } returns null
        every { chatHistory.getChat(any()) } returns null
        every { tracing.getSession(any()) } returns null
        every { toolRegistry.getToolOrNull(any()) } returns null

        coEvery { getStatus() } returns SystemStatus(
            uptime = 5.minutes,
            totalChats = 10,
            connectedBridges = 2,
            availableTools = 5,
            scheduledTasks = 1,
            memoryUsage = MemoryUsage(heapUsed = 100_000, heapMax = 256_000),
        )
        coEvery { memory.load(any()) } returns Memory(
            scope = MemoryScope.Global,
            facts = emptyList(),
        )
        coEvery { memory.listScopes() } returns emptyList()
        every { config.getConfig() } returns ConfigResponse(
            yaml = "provider:\n  type: lmstudio",
            config = FraggleSettings(),
        )
    }

    private fun ApplicationTestBuilder.setupApp() {
        application { configureTestApp() }
    }

    private fun Application.configureTestApp() {
        install(ContentNegotiation) { json(json) }
        routing {
            route("/api/v1") {
                statusRoutes(services)
                chatRoutes(services)
                bridgeRoutes(services)
                toolRoutes(services)
                memoryRoutes(services)
                schedulerRoutes(services)
                tracingRoutes(services)
                settingsRoutes(services)
                discordOAuthRoutes(services)
            }
        }
    }

    // -- Health & Status --

    @Nested
    inner class HealthRoutes {
        @Test
        fun `health returns 200 with status ok`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"status\""))
            assertTrue(body.contains("\"ok\""))
        }
    }

    @Nested
    inner class StatusRoutes {
        @Test
        fun `status returns system status`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/status")
            assertEquals(HttpStatusCode.OK, response.status)
            val status = json.decodeFromString<SystemStatus>(response.bodyAsText())
            assertEquals(10, status.totalChats)
            assertEquals(2, status.connectedBridges)
            assertEquals(5, status.availableTools)
        }
    }

    // -- Scheduler --

    @Nested
    inner class SchedulerRoutes {
        @Test
        fun `list tasks returns empty list`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/scheduler/tasks")
            assertEquals(HttpStatusCode.OK, response.status)
            val tasks = json.decodeFromString<List<ScheduledTaskInfo>>(response.bodyAsText())
            assertTrue(tasks.isEmpty())
        }

        @Test
        fun `get task returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/scheduler/tasks/unknown-id")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `delete task returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/scheduler/tasks/unknown-id")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `delete task returns 200 when cancelled`() = testApplication {
            setupApp()
            every { services.scheduler.cancelTask("task-1") } returns true
            val response = client.delete("/api/v1/scheduler/tasks/task-1")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }
    }

    // -- Tracing --

    @Nested
    inner class TracingRoutes {
        @Test
        fun `list sessions returns empty list`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/tracing/sessions")
            assertEquals(HttpStatusCode.OK, response.status)
            val sessions = json.decodeFromString<List<TraceSession>>(response.bodyAsText())
            assertTrue(sessions.isEmpty())
        }

        @Test
        fun `list sessions passes query parameters`() = testApplication {
            setupApp()
            client.get("/api/v1/tracing/sessions?limit=10&offset=5")
            coVerify { services.tracing.listSessions(10, 5) }
        }

        @Test
        fun `get session returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/tracing/sessions/unknown-id")
            assertEquals(HttpStatusCode.NotFound, response.status)
            val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("Session not found", error.error)
        }
    }

    // -- Chats --

    @Nested
    inner class ChatRoutes {
        @Test
        fun `list chats returns empty list`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/chats")
            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `list chats passes query parameters`() = testApplication {
            setupApp()
            client.get("/api/v1/chats?limit=25&offset=10")
            coVerify { services.chatHistory.listChats(25, 10) }
        }

        @Test
        fun `get chat returns 400 for non-numeric id`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/chats/abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `get chat returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/chats/999")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `get messages returns 400 for non-numeric chat id`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/chats/abc/messages")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // -- Bridges --

    @Nested
    inner class BridgeRoutes {
        @Test
        fun `list bridges returns empty list`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/bridges")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

        @Test
        fun `get bridge returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/bridges/signal")
            assertEquals(HttpStatusCode.NotFound, response.status)
            val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("Bridge not found", error.error)
        }

        @Test
        fun `connect bridge returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.post("/api/v1/bridges/signal/connect")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `disconnect bridge returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.post("/api/v1/bridges/signal/disconnect")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // -- Tools --

    @Nested
    inner class ToolRoutes {
        @Test
        fun `list tools returns empty list`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/tools")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

        @Test
        fun `get tool returns 404 when not found`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/tools/unknown_tool")
            assertEquals(HttpStatusCode.NotFound, response.status)
            val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("Tool not found", error.error)
        }
    }

    // -- Memory --

    @Nested
    inner class MemoryRoutes {
        @Test
        fun `list scopes returns empty scopes`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/memory/scopes")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MemoryScopesResponse>(response.bodyAsText())
            assertTrue(body.scopes.isEmpty())
        }

        @Test
        fun `list scopes with global scope`() = testApplication {
            setupApp()
            coEvery { services.memory.listScopes() } returns listOf(MemoryScope.Global)
            val response = client.get("/api/v1/memory/scopes")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MemoryScopesResponse>(response.bodyAsText())
            assertEquals(1, body.scopes.size)
            assertEquals("global", body.scopes[0].type)
            assertEquals(0, body.scopes[0].factCount)
        }

        @Test
        fun `get global memory returns empty facts`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/memory/global")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MemoryResponse>(response.bodyAsText())
            assertEquals("global", body.scope)
            assertTrue(body.facts.isEmpty())
        }

        @Test
        fun `get chat memory returns empty facts`() = testApplication {
            setupApp()
            coEvery { services.memory.load(MemoryScope.Chat("chat1")) } returns Memory(
                scope = MemoryScope.Chat("chat1"),
                facts = emptyList(),
            )
            val response = client.get("/api/v1/memory/chat/chat1")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MemoryResponse>(response.bodyAsText())
            assertEquals("chat:chat1", body.scope)
        }

        @Test
        fun `get user memory returns empty facts`() = testApplication {
            setupApp()
            coEvery { services.memory.load(MemoryScope.User("user1")) } returns Memory(
                scope = MemoryScope.User("user1"),
                facts = emptyList(),
            )
            val response = client.get("/api/v1/memory/user/user1")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<MemoryResponse>(response.bodyAsText())
            assertEquals("user:user1", body.scope)
        }

        @Test
        fun `clear global memory returns ok`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/global")
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { services.memory.clear(MemoryScope.Global) }
        }

        @Test
        fun `clear chat memory returns ok`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/chat/chat1")
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { services.memory.clear(MemoryScope.Chat("chat1")) }
        }

        @Test
        fun `clear user memory returns ok`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/user/user1")
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { services.memory.clear(MemoryScope.User("user1")) }
        }

        @Test
        fun `delete fact returns ok`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/global/global/facts/0")
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { services.memory.deleteFact(MemoryScope.Global, 0) }
        }

        @Test
        fun `delete fact returns 404 when index out of bounds`() = testApplication {
            setupApp()
            coEvery { services.memory.deleteFact(any(), any()) } throws IllegalArgumentException("Fact index out of bounds")
            val response = client.delete("/api/v1/memory/global/global/facts/99")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `delete fact returns 400 for invalid scope type`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/invalid/id/facts/0")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `delete fact returns 400 for non-numeric index`() = testApplication {
            setupApp()
            val response = client.delete("/api/v1/memory/global/global/facts/abc")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `update fact returns updated memory`() = testApplication {
            setupApp()
            val response = client.put("/api/v1/memory/global/global/facts/0") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"updated fact"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { services.memory.updateFact(MemoryScope.Global, 0, "updated fact") }
        }

        @Test
        fun `update fact returns 404 when index out of bounds`() = testApplication {
            setupApp()
            coEvery { services.memory.updateFact(any(), any(), any()) } throws IllegalArgumentException("Fact index out of bounds")
            val response = client.put("/api/v1/memory/global/global/facts/99") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"updated fact"}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // -- Discord OAuth --

    @Nested
    inner class DiscordOAuthRoutes {
        @Test
        fun `status returns unconfigured when service is null`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/discord/oauth/status")
            assertEquals(HttpStatusCode.OK, response.status)
            val status = json.decodeFromString<DiscordOAuthStatus>(response.bodyAsText())
            assertEquals(false, status.configured)
        }

        @Test
        fun `authorize returns 503 when not configured`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/discord/oauth/authorize")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `callback returns 503 when not configured`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/discord/oauth/callback?code=test")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `status returns configured when service present`() = testApplication {
            setupApp()
            val oauth = mockk<DiscordOAuthService>(relaxed = true) {
                every { isConfigured() } returns true
            }
            every { services.discordOAuth } returns oauth
            val response = client.get("/api/v1/discord/oauth/status")
            assertEquals(HttpStatusCode.OK, response.status)
            val status = json.decodeFromString<DiscordOAuthStatus>(response.bodyAsText())
            assertEquals(true, status.configured)
        }

        @Test
        fun `callback returns 400 when code is missing`() = testApplication {
            setupApp()
            val oauth = mockk<DiscordOAuthService>(relaxed = true)
            every { services.discordOAuth } returns oauth
            val response = client.get("/api/v1/discord/oauth/callback")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `callback returns 400 when discord returns error`() = testApplication {
            setupApp()
            val oauth = mockk<DiscordOAuthService>(relaxed = true)
            every { services.discordOAuth } returns oauth
            val response = client.get("/api/v1/discord/oauth/callback?code=test&error=access_denied&error_description=User+denied")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("User denied"))
        }

        @Test
        fun `callback returns success page on success`() = testApplication {
            setupApp()
            val oauth = mockk<DiscordOAuthService>(relaxed = true) {
                coEvery { handleCallback("test-code", null) } returns OAuthCallbackResult.Success("123", "testuser")
            }
            every { services.discordOAuth } returns oauth
            val response = client.get("/api/v1/discord/oauth/callback?code=test-code")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("testuser"))
            assertTrue(body.contains("Connected"))
        }

        @Test
        fun `callback returns 500 on error result`() = testApplication {
            setupApp()
            val oauth = mockk<DiscordOAuthService>(relaxed = true) {
                coEvery { handleCallback("bad-code", null) } returns OAuthCallbackResult.Error("Invalid code")
            }
            every { services.discordOAuth } returns oauth
            val response = client.get("/api/v1/discord/oauth/callback?code=bad-code")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }

    // -- Settings --

    @Nested
    inner class SettingsRoutes {
        @Test
        fun `get config returns configuration`() = testApplication {
            setupApp()
            val response = client.get("/api/v1/settings/config")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("lmstudio"))
        }
    }
}
