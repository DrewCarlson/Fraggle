package fraggle.agent.state

import fraggle.agent.message.AgentMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingMessageQueueTest {

    @Nested
    inner class OneAtATimeMode {
        @Test
        fun `drain returns one message at a time`() {
            val queue = PendingMessageQueue(QueueMode.ONE_AT_A_TIME)
            queue.enqueue(AgentMessage.User("first"))
            queue.enqueue(AgentMessage.User("second"))
            queue.enqueue(AgentMessage.User("third"))

            val batch1 = queue.drain()
            assertEquals(1, batch1.size)
            assertEquals("first", (batch1[0] as AgentMessage.User).content.first().let { (it as fraggle.agent.message.ContentPart.Text).text })

            val batch2 = queue.drain()
            assertEquals(1, batch2.size)
            assertEquals("second", (batch2[0] as AgentMessage.User).content.first().let { (it as fraggle.agent.message.ContentPart.Text).text })

            assertTrue(queue.hasItems())
        }

        @Test
        fun `drain returns empty list when empty`() {
            val queue = PendingMessageQueue(QueueMode.ONE_AT_A_TIME)
            assertEquals(emptyList(), queue.drain())
        }
    }

    @Nested
    inner class AllMode {
        @Test
        fun `drain returns all messages at once`() {
            val queue = PendingMessageQueue(QueueMode.ALL)
            queue.enqueue(AgentMessage.User("first"))
            queue.enqueue(AgentMessage.User("second"))

            val batch = queue.drain()
            assertEquals(2, batch.size)
            assertFalse(queue.hasItems())
        }

        @Test
        fun `drain returns empty list when empty`() {
            val queue = PendingMessageQueue(QueueMode.ALL)
            assertEquals(emptyList(), queue.drain())
        }
    }

    @Nested
    inner class GeneralBehavior {
        @Test
        fun `hasItems returns false when empty`() {
            val queue = PendingMessageQueue()
            assertFalse(queue.hasItems())
        }

        @Test
        fun `hasItems returns true after enqueue`() {
            val queue = PendingMessageQueue()
            queue.enqueue(AgentMessage.User("msg"))
            assertTrue(queue.hasItems())
        }

        @Test
        fun `clear removes all items`() {
            val queue = PendingMessageQueue()
            queue.enqueue(AgentMessage.User("a"))
            queue.enqueue(AgentMessage.User("b"))
            queue.clear()
            assertFalse(queue.hasItems())
            assertEquals(emptyList(), queue.drain())
        }

        @Test
        fun `default mode is ONE_AT_A_TIME`() {
            val queue = PendingMessageQueue()
            queue.enqueue(AgentMessage.User("a"))
            queue.enqueue(AgentMessage.User("b"))

            val batch = queue.drain()
            assertEquals(1, batch.size)
            assertTrue(queue.hasItems())
        }
    }
}
