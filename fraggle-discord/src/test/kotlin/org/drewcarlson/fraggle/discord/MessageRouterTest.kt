package org.drewcarlson.fraggle.discord

import org.drewcarlson.fraggle.chat.IncomingMessage
import org.drewcarlson.fraggle.chat.MessageContent
import org.drewcarlson.fraggle.chat.Sender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MessageRouterTest {

    private fun createMessage(
        chatId: String = "123456789",
        content: String = "Hello world",
        senderId: String = "user123",
    ) = IncomingMessage(
        id = "msg-1",
        chatId = chatId,
        sender = Sender(id = senderId, name = "Test User"),
        content = MessageContent.Text(content),
        timestamp = Clock.System.now(),
    )

    @Nested
    inner class shouldProcess {
        @Test
        fun `returns true for DM when respondToDirectMessages is true`() {
            val config = DiscordConfig(
                token = "test-token",
                respondToDirectMessages = true,
                triggerPrefix = "!bot",
            )
            val router = MessageRouter(config)
            val message = createMessage(chatId = "dm:123", content = "Hello")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `returns true when message has trigger prefix`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "!fraggle how are you?")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `returns false when message lacks trigger prefix`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
                respondToDirectMessages = false,
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "Hello world")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `returns true when no trigger prefix is set`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = null,
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "Any message")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `returns false for disabled registered chat`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = null,
                registeredChats = listOf(
                    RegisteredDiscordChat(id = "channel123", enabled = false)
                ),
            )
            val router = MessageRouter(config)
            val message = createMessage(chatId = "channel123", content = "Hello")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `returns false for non-allowed channel when allowlist is set`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = null,
                allowedChannelIds = listOf("allowed-channel"),
            )
            val router = MessageRouter(config)
            val message = createMessage(chatId = "other-channel", content = "Hello")

            assertFalse(router.shouldProcess(message))
        }

        @Test
        fun `returns true for allowed channel`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = null,
                allowedChannelIds = listOf("allowed-channel"),
            )
            val router = MessageRouter(config)
            val message = createMessage(chatId = "allowed-channel", content = "Hello")

            assertTrue(router.shouldProcess(message))
        }

        @Test
        fun `trigger prefix is case insensitive`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
            )
            val router = MessageRouter(config)

            assertTrue(router.shouldProcess(createMessage(content = "!FRAGGLE hello")))
            assertTrue(router.shouldProcess(createMessage(content = "!Fraggle hello")))
            assertTrue(router.shouldProcess(createMessage(content = "!fraggle hello")))
        }
    }

    @Nested
    inner class process {
        @Test
        fun `strips trigger prefix from message`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "!fraggle how are you?")

            val processed = router.process(message)

            assertNotNull(processed)
            assertEquals("how are you?", (processed!!.content as MessageContent.Text).text)
        }

        @Test
        fun `preserves message without trigger when no trigger is set`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = null,
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "Hello world")

            val processed = router.process(message)

            assertNotNull(processed)
            assertEquals("Hello world", (processed!!.content as MessageContent.Text).text)
        }

        @Test
        fun `returns null for messages that should not be processed`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!bot",
                respondToDirectMessages = false,
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "No trigger here")

            assertNull(router.process(message))
        }

        @Test
        fun `returns null for empty message after stripping trigger`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
            )
            val router = MessageRouter(config)
            val message = createMessage(content = "!fraggle")

            assertNull(router.process(message))
        }

        @Test
        fun `uses trigger override for registered chat`() {
            val config = DiscordConfig(
                token = "test-token",
                triggerPrefix = "!fraggle",
                registeredChats = listOf(
                    RegisteredDiscordChat(
                        id = "special-channel",
                        triggerOverride = "!custom",
                    )
                ),
            )
            val router = MessageRouter(config)

            // Message with override trigger in registered channel
            val messageWithOverride = createMessage(
                chatId = "special-channel",
                content = "!custom hello",
            )
            val processed = router.process(messageWithOverride)
            assertNotNull(processed)
            assertEquals("hello", (processed!!.content as MessageContent.Text).text)

            // Default trigger doesn't work in registered channel
            val messageWithDefault = createMessage(
                chatId = "special-channel",
                content = "!fraggle hello",
            )
            assertNull(router.process(messageWithDefault))
        }
    }
}
