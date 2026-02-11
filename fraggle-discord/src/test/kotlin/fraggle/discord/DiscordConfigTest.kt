package fraggle.discord

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DiscordConfigTest {

    @Nested
    inner class Constants {
        @Test
        fun `MAX_ATTACHMENTS_PER_MESSAGE is 10`() {
            assertEquals(10, DiscordConfig.MAX_ATTACHMENTS_PER_MESSAGE)
        }

        @Test
        fun `FILE_SIZE_FREE is 10 MB`() {
            assertEquals(10L * 1024 * 1024, DiscordConfig.FILE_SIZE_FREE)
        }

        @Test
        fun `FILE_SIZE_NITRO_BASIC is 50 MB`() {
            assertEquals(50L * 1024 * 1024, DiscordConfig.FILE_SIZE_NITRO_BASIC)
        }

        @Test
        fun `FILE_SIZE_NITRO is 500 MB`() {
            assertEquals(500L * 1024 * 1024, DiscordConfig.FILE_SIZE_NITRO)
        }
    }

    @Nested
    inner class Defaults {
        @Test
        fun `default trigger prefix is !fraggle`() {
            val config = DiscordConfig(token = "test")
            assertEquals("!fraggle", config.triggerPrefix)
        }

        @Test
        fun `default max images per message is 10`() {
            val config = DiscordConfig(token = "test")
            assertEquals(10, config.maxImagesPerMessage)
        }

        @Test
        fun `default max file size is 10 MB`() {
            val config = DiscordConfig(token = "test")
            assertEquals(10L * 1024 * 1024, config.maxFileSizeBytes)
        }

        @Test
        fun `default respond to DMs is true`() {
            val config = DiscordConfig(token = "test")
            assertTrue(config.respondToDirectMessages)
        }

        @Test
        fun `default show typing indicator is true`() {
            val config = DiscordConfig(token = "test")
            assertTrue(config.showTypingIndicator)
        }

        @Test
        fun `default allowed guild IDs is empty`() {
            val config = DiscordConfig(token = "test")
            assertTrue(config.allowedGuildIds.isEmpty())
        }

        @Test
        fun `default allowed channel IDs is empty`() {
            val config = DiscordConfig(token = "test")
            assertTrue(config.allowedChannelIds.isEmpty())
        }
    }

    @Nested
    inner class RegisteredDiscordChat {
        @Test
        fun `default enabled is true`() {
            val chat = RegisteredDiscordChat(id = "123")
            assertTrue(chat.enabled)
        }

        @Test
        fun `default name is null`() {
            val chat = RegisteredDiscordChat(id = "123")
            assertNull(chat.name)
        }

        @Test
        fun `default trigger override is null`() {
            val chat = RegisteredDiscordChat(id = "123")
            assertNull(chat.triggerOverride)
        }
    }
}
