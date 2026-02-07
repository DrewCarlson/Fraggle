package org.drewcarlson.fraggle.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolExecutionContextTest {

    @Nested
    inner class ContextPropagation {

        @Test
        fun `current returns null when no context set`() {
            assertNull(ToolExecutionContext.current())
        }

        @Test
        fun `current returns context within coroutine scope`() = runTest {
            val ctx = ToolExecutionContext(chatId = "test-chat", userId = "user1")

            withContext(ToolExecutionContext.asContextElement(ctx)) {
                val current = ToolExecutionContext.current()
                assertNotNull(current)
                assertEquals("test-chat", current.chatId)
                assertEquals("user1", current.userId)
            }
        }

        @Test
        fun `context is not available after scope exits`() = runTest {
            val ctx = ToolExecutionContext(chatId = "test-chat")

            withContext(ToolExecutionContext.asContextElement(ctx)) {
                assertNotNull(ToolExecutionContext.current())
            }

            // After the withContext block, the ThreadLocal should be restored
            assertNull(ToolExecutionContext.current())
        }
    }

    @Nested
    inner class AttachmentCollection {

        @Test
        fun `attachments list starts empty`() {
            val ctx = ToolExecutionContext(chatId = "chat")
            assertEquals(0, ctx.attachments.size)
        }

        @Test
        fun `attachments can be added`() {
            val ctx = ToolExecutionContext(chatId = "chat")

            ctx.attachments.add(ResponseAttachment.Image(
                data = byteArrayOf(1, 2, 3),
                mimeType = "image/png",
                caption = "test",
            ))

            assertEquals(1, ctx.attachments.size)
            val attachment = ctx.attachments[0] as ResponseAttachment.Image
            assertEquals("image/png", attachment.mimeType)
            assertEquals("test", attachment.caption)
        }

        @Test
        fun `attachments collected within coroutine context`() = runTest {
            val ctx = ToolExecutionContext(chatId = "chat")

            withContext(ToolExecutionContext.asContextElement(ctx)) {
                ToolExecutionContext.current()?.attachments?.add(
                    ResponseAttachment.Image(
                        data = byteArrayOf(1, 2, 3),
                        mimeType = "image/png",
                    )
                )
            }

            assertEquals(1, ctx.attachments.size)
        }
    }
}
