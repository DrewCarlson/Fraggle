package org.drewcarlson.fraggle.memory

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTest {

    @Nested
    inner class MemoryScopeTests {

        @Test
        fun `Global scope toString returns global`() {
            assertEquals("global", MemoryScope.Global.toString())
        }

        @Test
        fun `Chat scope toString includes chatId`() {
            val scope = MemoryScope.Chat("chat123")
            assertEquals("chat:chat123", scope.toString())
        }

        @Test
        fun `User scope toString includes userId`() {
            val scope = MemoryScope.User("user456")
            assertEquals("user:user456", scope.toString())
        }

        @Test
        fun `Chat scope preserves chatId`() {
            val scope = MemoryScope.Chat("my-special-chat")
            assertEquals("my-special-chat", scope.chatId)
        }

        @Test
        fun `User scope preserves userId`() {
            val scope = MemoryScope.User("user-with-id")
            assertEquals("user-with-id", scope.userId)
        }
    }

    @Nested
    inner class FactTests {

        @Test
        fun `Fact with content only has defaults`() {
            val fact = Fact(content = "Some fact")

            assertEquals("Some fact", fact.content)
            assertTrue(fact.tags.isEmpty())
            assertEquals(null, fact.source)
        }

        @Test
        fun `Fact with all fields preserves them`() {
            val timestamp = Instant.now()
            val fact = Fact(
                content = "Important fact",
                timestamp = timestamp,
                source = "conversation",
                tags = listOf("important", "user-provided"),
            )

            assertEquals("Important fact", fact.content)
            assertEquals(timestamp, fact.timestamp)
            assertEquals("conversation", fact.source)
            assertEquals(listOf("important", "user-provided"), fact.tags)
        }
    }

    @Nested
    inner class MemoryToPromptStringTests {

        @Test
        fun `empty memory returns empty string`() {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = emptyList(),
            )

            assertEquals("", memory.toPromptString())
        }

        @Test
        fun `global memory has Global Memory header`() {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("A fact")),
            )

            val result = memory.toPromptString()
            assertTrue(result.contains("## Global Memory"))
        }

        @Test
        fun `chat memory has Chat Memory header`() {
            val memory = Memory(
                scope = MemoryScope.Chat("test"),
                facts = listOf(Fact("A fact")),
            )

            val result = memory.toPromptString()
            assertTrue(result.contains("## Chat Memory"))
        }

        @Test
        fun `user memory has User Memory header`() {
            val memory = Memory(
                scope = MemoryScope.User("test"),
                facts = listOf(Fact("A fact")),
            )

            val result = memory.toPromptString()
            assertTrue(result.contains("## User Memory"))
        }

        @Test
        fun `facts are formatted as bullet points`() {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(
                    Fact("First fact"),
                    Fact("Second fact"),
                    Fact("Third fact"),
                ),
            )

            val result = memory.toPromptString()
            assertTrue(result.contains("- First fact"))
            assertTrue(result.contains("- Second fact"))
            assertTrue(result.contains("- Third fact"))
        }

        @Test
        fun `single fact memory formats correctly`() {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Only one fact")),
            )

            val result = memory.toPromptString()
            assertTrue(result.contains("## Global Memory"))
            assertTrue(result.contains("- Only one fact"))
        }
    }

    @Nested
    inner class MemoryDataClassTests {

        @Test
        fun `Memory preserves scope`() {
            val scope = MemoryScope.Chat("mychat")
            val memory = Memory(scope = scope, facts = emptyList())
            assertEquals(scope, memory.scope)
        }

        @Test
        fun `Memory preserves facts`() {
            val facts = listOf(Fact("Fact 1"), Fact("Fact 2"))
            val memory = Memory(scope = MemoryScope.Global, facts = facts)
            assertEquals(2, memory.facts.size)
            assertEquals("Fact 1", memory.facts[0].content)
            assertEquals("Fact 2", memory.facts[1].content)
        }

        @Test
        fun `Memory preserves lastUpdated`() {
            val timestamp = Instant.now()
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = emptyList(),
                lastUpdated = timestamp,
            )
            assertEquals(timestamp, memory.lastUpdated)
        }

        @Test
        fun `Memory lastUpdated defaults to null`() {
            val memory = Memory(scope = MemoryScope.Global, facts = emptyList())
            assertEquals(null, memory.lastUpdated)
        }
    }
}
