package org.drewcarlson.fraggle.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChatBridgeManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: ChatBridgeManager

    @BeforeEach
    fun setup() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = ChatBridgeManager(scope)
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun createMockBridge(
        platformName: String = "Test",
        connected: Boolean = false,
    ): ChatBridge {
        return mockk<ChatBridge>(relaxed = true) {
            every { platform } returns ChatPlatform(
                name = platformName,
                supportsInlineImages = true,
                supportsAttachments = true,
                supportsReactions = true,
            )
            every { isConnected() } returns connected
            every { messages() } returns emptyFlow()
        }
    }

    @Nested
    inner class Registration {

        @Test
        fun `register adds bridge`() {
            val bridge = createMockBridge()

            manager.register("signal", bridge)

            assertTrue(manager.registeredBridges().contains("signal"))
        }

        @Test
        fun `register normalizes name to lowercase`() {
            val bridge = createMockBridge()

            manager.register("SIGNAL", bridge)

            assertTrue(manager.registeredBridges().contains("signal"))
        }

        @Test
        fun `register replaces existing bridge with same name`() {
            val bridge1 = createMockBridge("First")
            val bridge2 = createMockBridge("Second")

            manager.register("test", bridge1)
            manager.register("test", bridge2)

            assertEquals(1, manager.registeredBridges().size)
            assertEquals("Second", manager.getBridge("test")?.platform?.name)
        }

        @Test
        fun `unregister removes bridge`() {
            val bridge = createMockBridge()
            manager.register("signal", bridge)

            manager.unregister("signal")

            assertFalse(manager.registeredBridges().contains("signal"))
        }

        @Test
        fun `unregister normalizes name`() {
            val bridge = createMockBridge()
            manager.register("signal", bridge)

            manager.unregister("SIGNAL")

            assertTrue(manager.registeredBridges().isEmpty())
        }

        @Test
        fun `getBridge returns registered bridge`() {
            val bridge = createMockBridge("TestPlatform")
            manager.register("test", bridge)

            val found = manager.getBridge("test")

            assertNotNull(found)
            assertEquals("TestPlatform", found.platform.name)
        }

        @Test
        fun `getBridge returns null for unregistered`() {
            assertNull(manager.getBridge("nonexistent"))
        }

        @Test
        fun `registeredBridges returns all bridge names`() {
            manager.register("signal", createMockBridge())
            manager.register("discord", createMockBridge())
            manager.register("telegram", createMockBridge())

            val registered = manager.registeredBridges()

            assertEquals(3, registered.size)
            assertTrue(registered.containsAll(listOf("signal", "discord", "telegram")))
        }
    }

    @Nested
    inner class QualifiedChatIds {

        @Test
        fun `qualifyChatId creates correct format`() {
            val qualified = manager.qualifyChatId("signal", "+1234567890")

            assertEquals("signal:+1234567890", qualified)
        }

        @Test
        fun `qualifyChatId normalizes bridge name`() {
            val qualified = manager.qualifyChatId("SIGNAL", "+1234567890")

            assertEquals("signal:+1234567890", qualified)
        }

        @Test
        fun `parseQualifiedChatId extracts bridge and chatId`() {
            val (bridgeName, chatId) = manager.parseQualifiedChatId("signal:+1234567890")

            assertEquals("signal", bridgeName)
            assertEquals("+1234567890", chatId)
        }

        @Test
        fun `parseQualifiedChatId handles chatId with colons`() {
            val (bridgeName, chatId) = manager.parseQualifiedChatId("discord:guild:channel:123")

            assertEquals("discord", bridgeName)
            assertEquals("guild:channel:123", chatId)
        }

        @Test
        fun `parseQualifiedChatId normalizes bridge name`() {
            val (bridgeName, _) = manager.parseQualifiedChatId("SIGNAL:+1234567890")

            assertEquals("signal", bridgeName)
        }

        @Test
        fun `parseQualifiedChatId throws for unqualified id with no bridges`() {
            assertThrows<IllegalArgumentException> {
                manager.parseQualifiedChatId("unqualified-chat-id")
            }
        }

        @Test
        fun `parseQualifiedChatId defaults to first bridge for unqualified id`() {
            manager.register("signal", createMockBridge())

            val (bridgeName, chatId) = manager.parseQualifiedChatId("unqualified-chat-id")

            assertEquals("signal", bridgeName)
            assertEquals("unqualified-chat-id", chatId)
        }
    }

    @Nested
    inner class ConnectionState {

        @Test
        fun `hasConnectedBridge returns false when no bridges`() {
            assertFalse(manager.hasConnectedBridge())
        }

        @Test
        fun `hasConnectedBridge returns false when all disconnected`() {
            manager.register("signal", createMockBridge(connected = false))
            manager.register("discord", createMockBridge(connected = false))

            assertFalse(manager.hasConnectedBridge())
        }

        @Test
        fun `hasConnectedBridge returns true when any connected`() {
            manager.register("signal", createMockBridge(connected = false))
            manager.register("discord", createMockBridge(connected = true))

            assertTrue(manager.hasConnectedBridge())
        }

        @Test
        fun `isConnected returns bridge connection state`() {
            manager.register("connected", createMockBridge(connected = true))
            manager.register("disconnected", createMockBridge(connected = false))

            assertTrue(manager.isConnected("connected"))
            assertFalse(manager.isConnected("disconnected"))
        }

        @Test
        fun `isConnected returns false for unknown bridge`() {
            assertFalse(manager.isConnected("unknown"))
        }
    }

    @Nested
    inner class PlatformLookup {

        @Test
        fun `getPlatform returns platform for valid qualified id`() {
            manager.register("signal", createMockBridge("Signal"))

            val platform = manager.getPlatform("signal:+1234567890")

            assertNotNull(platform)
            assertEquals("Signal", platform.name)
        }

        @Test
        fun `getPlatform returns null for unknown bridge`() {
            val platform = manager.getPlatform("unknown:chat123")

            assertNull(platform)
        }
    }

    @Nested
    inner class SendAndTyping {

        @Test
        fun `send routes to correct bridge`() = runTest {
            val bridge = createMockBridge()
            manager.register("signal", bridge)

            val message = OutgoingMessage.Text("Hello")
            manager.send("signal:+1234567890", message)

            coVerify { bridge.send("+1234567890", message) }
        }

        @Test
        fun `send throws for unknown bridge`() = runTest {
            val exception = assertThrows<IllegalArgumentException> {
                manager.send("unknown:chat123", OutgoingMessage.Text("Hello"))
            }
            assertEquals(exception.message?.contains("unknown"), true)
        }

        @Test
        fun `setTyping routes to correct bridge`() = runTest {
            val bridge = createMockBridge()
            manager.register("signal", bridge)

            manager.setTyping("signal:+1234567890", true)

            coVerify { bridge.setTyping("+1234567890", true) }
        }

        @Test
        fun `setTyping does nothing for unknown bridge`() = runTest {
            // Should not throw, just silently skip
            manager.setTyping("unknown:chat123", true)
        }
    }

    @Nested
    inner class Connection {

        @Test
        fun `connectAll connects all bridges`() = runTest {
            val bridge1 = createMockBridge()
            val bridge2 = createMockBridge()
            manager.register("signal", bridge1)
            manager.register("discord", bridge2)

            manager.connectAll()

            coVerify { bridge1.connect() }
            coVerify { bridge2.connect() }
        }

        @Test
        fun `connect connects specific bridge`() = runTest {
            val bridge1 = createMockBridge()
            val bridge2 = createMockBridge()
            manager.register("signal", bridge1)
            manager.register("discord", bridge2)

            manager.connect("signal")

            coVerify { bridge1.connect() }
            coVerify(exactly = 0) { bridge2.connect() }
        }

        @Test
        fun `connect throws for unknown bridge`() = runTest {
            assertThrows<IllegalArgumentException> {
                manager.connect("unknown")
            }
        }

        @Test
        fun `disconnectAll disconnects all bridges`() = runTest {
            val bridge1 = createMockBridge()
            val bridge2 = createMockBridge()
            manager.register("signal", bridge1)
            manager.register("discord", bridge2)

            manager.disconnectAll()

            coVerify { bridge1.disconnect() }
            coVerify { bridge2.disconnect() }
        }

        @Test
        fun `disconnect disconnects specific bridge`() = runTest {
            val bridge1 = createMockBridge()
            val bridge2 = createMockBridge()
            manager.register("signal", bridge1)
            manager.register("discord", bridge2)

            manager.disconnect("signal")

            coVerify { bridge1.disconnect() }
            coVerify(exactly = 0) { bridge2.disconnect() }
        }

        @Test
        fun `disconnect throws for unknown bridge`() = runTest {
            assertThrows<IllegalArgumentException> {
                manager.disconnect("unknown")
            }
        }
    }

    @Nested
    inner class ChatInfo {

        @Test
        fun `getChatInfo returns info from bridge`() = runTest {
            val expectedInfo = ChatInfo(
                id = "+1234567890",
                name = "Test Chat",
                isGroup = false,
            )
            val bridge = createMockBridge()
            coEvery { bridge.getChatInfo(any()) } returns expectedInfo
            manager.register("signal", bridge)

            val info = manager.getChatInfo("signal:+1234567890")

            assertNotNull(info)
            assertEquals("Test Chat", info.name)
        }

        @Test
        fun `getChatInfo returns null for unknown bridge`() = runTest {
            val info = manager.getChatInfo("unknown:chat123")

            assertNull(info)
        }
    }
}
