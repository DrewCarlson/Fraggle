package org.drewcarlson.fraggle.signal

import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.chat.Sender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class MessageRouterTest {

    private fun createConfig(
        triggerPrefix: String? = "@fraggle",
        respondToDirectMessages: Boolean = true,
        registeredChats: List<RegisteredChat> = emptyList(),
    ): SignalConfig = SignalConfig(
        phoneNumber = "+1234567890",
        configDir = "/tmp/signal-test",
        triggerPrefix = triggerPrefix,
        respondToDirectMessages = respondToDirectMessages,
        registeredChats = registeredChats,
    )

    private fun createMessage(
        chatId: String = "+1111111111",
        text: String = "Hello",
    ): IncomingMessage = IncomingMessage(
        id = "msg-${System.currentTimeMillis()}",
        chatId = chatId,
        sender = Sender(id = "+1111111111", name = "Test User"),
        content = MessageContent.Text(text),
        timestamp = Clock.System.now(),
    )

    private fun createImageMessage(chatId: String = "+1111111111"): IncomingMessage = IncomingMessage(
        id = "msg-${System.currentTimeMillis()}",
        chatId = chatId,
        sender = Sender(id = "+1111111111", name = "Test User"),
        content = MessageContent.Image(data = byteArrayOf(), mimeType = "image/png"),
        timestamp = Clock.System.now(),
    )

    @Nested
    inner class DirectMessageHandling {

        @Test
        fun `processes direct message without trigger when respondToDirectMessages is true`() {
            val router = MessageRouter(createConfig(respondToDirectMessages = true))
            val message = createMessage(chatId = "+1111111111", text = "Hello")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `processes direct message with trigger`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@bot"))
            val message = createMessage(chatId = "+1111111111", text = "@bot Hello")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `processes direct message without trigger even when trigger is set`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@bot",
                respondToDirectMessages = true,
            ))
            val message = createMessage(chatId = "+1111111111", text = "Hello without trigger")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `rejects direct message when respondToDirectMessages is false and no trigger`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@bot",
                respondToDirectMessages = false,
            ))
            val message = createMessage(chatId = "+1111111111", text = "Hello")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `processes direct message with trigger when respondToDirectMessages is false`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@bot",
                respondToDirectMessages = false,
            ))
            val message = createMessage(chatId = "+1111111111", text = "@bot Hello")

            assertTrue(router.shouldProcess(message))
        }
    }

    @Nested
    inner class GroupMessageHandling {

        @Test
        fun `processes group message with trigger`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@fraggle"))
            val message = createMessage(chatId = "group:abc123", text = "@fraggle help me")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `rejects group message without trigger when trigger is set`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@fraggle"))
            val message = createMessage(chatId = "group:abc123", text = "Hello everyone")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `processes group message when no trigger is configured`() {
            val router = MessageRouter(createConfig(triggerPrefix = null))
            val message = createMessage(chatId = "group:abc123", text = "Hello everyone")

            assertTrue(router.shouldProcess(message))
        }
    }

    @Nested
    inner class RegisteredChatFiltering {

        @Test
        fun `processes message from registered chat`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(RegisteredChat(id = "+1111111111")),
            ))
            val message = createMessage(chatId = "+1111111111")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `rejects message from unregistered chat when registration is enabled`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(RegisteredChat(id = "+2222222222")),
            ))
            val message = createMessage(chatId = "+1111111111")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `processes any chat when no chats are registered`() {
            val router = MessageRouter(createConfig(registeredChats = emptyList()))
            val message = createMessage(chatId = "+9999999999")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `respects disabled registered chats when other chats are enabled`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(
                    RegisteredChat(id = "+1111111111", enabled = false),
                    RegisteredChat(id = "+2222222222", enabled = true),
                ),
            ))
            val message = createMessage(chatId = "+1111111111")

            // When a chat is disabled but there are other enabled chats,
            // the disabled chat should be rejected
            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `all disabled chats behaves like no registration`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(
                    RegisteredChat(id = "+1111111111", enabled = false),
                ),
            ))
            val message = createMessage(chatId = "+1111111111")

            // When ALL chats are disabled, registeredChatIds becomes empty,
            // which behaves like "no registration" (allow all)
            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `isChatRegistered returns true for registered chat`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(RegisteredChat(id = "+1111111111")),
            ))

            assertTrue(router.isChatRegistered("+1111111111"))
        }

        @Test
        fun `isChatRegistered returns false for unregistered chat`() {
            val router = MessageRouter(createConfig(
                registeredChats = listOf(RegisteredChat(id = "+2222222222")),
            ))

            assertFalse(router.isChatRegistered("+1111111111"))
        }

        @Test
        fun `isChatRegistered returns true for any chat when no registration`() {
            val router = MessageRouter(createConfig(registeredChats = emptyList()))

            assertTrue(router.isChatRegistered("+anything"))
        }
    }

    @Nested
    inner class TriggerStripping {

        @Test
        fun `strips trigger prefix from message`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@fraggle"))
            val message = createMessage(text = "@fraggle help me")

            val processed = router.process(message)

            assertNotNull(processed)
            val text = (processed.content as MessageContent.Text).text
            assertEquals("help me", text)
        }

        @Test
        fun `handles trigger with extra whitespace`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@fraggle"))
            val message = createMessage(text = "@fraggle   multiple   spaces")

            val processed = router.process(message)

            assertNotNull(processed)
            val text = (processed.content as MessageContent.Text).text
            assertEquals("multiple   spaces", text)
        }

        @Test
        fun `leaves message unchanged when no trigger present`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@fraggle",
                respondToDirectMessages = true,
            ))
            val message = createMessage(chatId = "+1111111111", text = "Hello without trigger")

            val processed = router.process(message)

            assertNotNull(processed)
            val text = (processed.content as MessageContent.Text).text
            assertEquals("Hello without trigger", text)
        }

        @Test
        fun `preserves original chatId after processing`() {
            val router = MessageRouter(createConfig(triggerPrefix = "@fraggle"))
            val message = createMessage(chatId = "group:mygroup", text = "@fraggle test")

            val processed = router.process(message)

            assertNotNull(processed)
            assertEquals("group:mygroup", processed.chatId)
        }
    }

    @Nested
    inner class TriggerOverrides {

        @Test
        fun `uses chat-specific trigger override`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@fraggle",
                registeredChats = listOf(
                    RegisteredChat(id = "group:special", triggerOverride = "@bot"),
                ),
            ))

            // Message with default trigger should be rejected
            val messageWithDefault = createMessage(chatId = "group:special", text = "@fraggle hello")
            assertFalse(router.shouldProcess(messageWithDefault))

            // Message with override trigger should be accepted
            val messageWithOverride = createMessage(chatId = "group:special", text = "@bot hello")
            assertTrue(router.shouldProcess(messageWithOverride))
        }

        @Test
        fun `falls back to default trigger for non-overridden chats`() {
            val router = MessageRouter(createConfig(
                triggerPrefix = "@fraggle",
                registeredChats = listOf(
                    RegisteredChat(id = "group:special", triggerOverride = "@bot"),
                    RegisteredChat(id = "group:normal"),
                ),
            ))

            val message = createMessage(chatId = "group:normal", text = "@fraggle hello")
            assertTrue(router.shouldProcess(message))
        }
    }

    @Nested
    inner class NonTextMessages {

        @Test
        fun `rejects non-text messages`() {
            val router = MessageRouter(createConfig())
            val imageMessage = createImageMessage()

            assertFalse(router.shouldProcess(imageMessage))
        }

        @Test
        fun `process returns null for non-text messages`() {
            val router = MessageRouter(createConfig())
            val imageMessage = createImageMessage()

            assertNull(router.process(imageMessage))
        }
    }

    @Nested
    inner class ChatConfigLookup {

        @Test
        fun `getChatConfig returns config for registered chat`() {
            val chatConfig = RegisteredChat(id = "+1111111111", name = "Test Chat")
            val router = MessageRouter(createConfig(
                registeredChats = listOf(chatConfig),
            ))

            val found = router.getChatConfig("+1111111111")

            assertNotNull(found)
            assertEquals("Test Chat", found.name)
        }

        @Test
        fun `getChatConfig returns null for unregistered chat`() {
            val router = MessageRouter(createConfig(registeredChats = emptyList()))

            assertNull(router.getChatConfig("+1111111111"))
        }
    }
}
