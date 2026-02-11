package fraggle.memory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class FileMemoryStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: FileMemoryStore

    @BeforeEach
    fun setup() {
        store = FileMemoryStore(tempDir)
    }

    @Nested
    inner class LoadTests {

        @Test
        fun `load returns empty memory for non-existent file`() = runTest {
            val memory = store.load(MemoryScope.Global)

            assertEquals(MemoryScope.Global, memory.scope)
            assertTrue(memory.facts.isEmpty())
        }

        @Test
        fun `load parses facts from markdown file`() = runTest {
            // Create memory file manually
            val globalFile = tempDir.resolve("global.md")
            globalFile.writeText("""
                # Memory

                - First fact
                - Second fact
                - Third fact
            """.trimIndent())

            val memory = store.load(MemoryScope.Global)

            assertEquals(3, memory.facts.size)
            assertEquals("First fact", memory.facts[0].content)
            assertEquals("Second fact", memory.facts[1].content)
            assertEquals("Third fact", memory.facts[2].content)
        }

        @Test
        fun `load ignores non-fact lines`() = runTest {
            val globalFile = tempDir.resolve("global.md")
            globalFile.writeText("""
                # Memory

                Some random text

                - Actual fact

                More random text
            """.trimIndent())

            val memory = store.load(MemoryScope.Global)

            assertEquals(1, memory.facts.size)
            assertEquals("Actual fact", memory.facts[0].content)
        }

        @Test
        fun `load sets lastUpdated from file modification time`() = runTest {
            val globalFile = tempDir.resolve("global.md")
            globalFile.writeText("# Memory\n\n- Test fact")

            val memory = store.load(MemoryScope.Global)

            assertNotNull(memory.lastUpdated)
        }
    }

    @Nested
    inner class SaveTests {

        @Test
        fun `save creates global memory file`() = runTest {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Test fact")),
            )

            store.save(MemoryScope.Global, memory)

            val globalFile = tempDir.resolve("global.md")
            assertTrue(globalFile.exists())
        }

        @Test
        fun `save writes facts as markdown`() = runTest {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Fact one"), Fact("Fact two")),
            )

            store.save(MemoryScope.Global, memory)

            val content = tempDir.resolve("global.md").readText()
            assertTrue(content.contains("# Memory"))
            assertTrue(content.contains("- Fact one"))
            assertTrue(content.contains("- Fact two"))
        }

        @Test
        fun `save creates chat directory structure`() = runTest {
            val memory = Memory(
                scope = MemoryScope.Chat("chat123"),
                facts = listOf(Fact("Chat fact")),
            )

            store.save(MemoryScope.Chat("chat123"), memory)

            val chatFile = tempDir.resolve("chats/chat123/memory.md")
            assertTrue(chatFile.exists())
        }

        @Test
        fun `save creates user directory structure`() = runTest {
            val memory = Memory(
                scope = MemoryScope.User("user456"),
                facts = listOf(Fact("User fact")),
            )

            store.save(MemoryScope.User("user456"), memory)

            val userFile = tempDir.resolve("users/user456/memory.md")
            assertTrue(userFile.exists())
        }

        @Test
        fun `save overwrites existing file`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Original")))
            )
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Replacement")))
            )

            val content = tempDir.resolve("global.md").readText()
            assertFalse(content.contains("Original"))
            assertTrue(content.contains("Replacement"))
        }
    }

    @Nested
    inner class AppendTests {

        @Test
        fun `append creates file if not exists`() = runTest {
            store.append(MemoryScope.Global, Fact("First fact"))

            val globalFile = tempDir.resolve("global.md")
            assertTrue(globalFile.exists())
        }

        @Test
        fun `append adds fact to existing file`() = runTest {
            tempDir.resolve("global.md").writeText("# Memory\n\n- Existing fact")

            store.append(MemoryScope.Global, Fact("Appended fact"))

            val content = tempDir.resolve("global.md").readText()
            assertTrue(content.contains("- Existing fact"))
            assertTrue(content.contains("- Appended fact"))
        }

        @Test
        fun `append works for chat scope`() = runTest {
            store.append(MemoryScope.Chat("test"), Fact("Chat fact"))

            val chatFile = tempDir.resolve("chats/test/memory.md")
            assertTrue(chatFile.exists())
            assertTrue(chatFile.readText().contains("- Chat fact"))
        }
    }

    @Nested
    inner class ClearTests {

        @Test
        fun `clear deletes memory file`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("Test"))))
            assertTrue(tempDir.resolve("global.md").exists())

            store.clear(MemoryScope.Global)

            assertFalse(tempDir.resolve("global.md").exists())
        }

        @Test
        fun `clear does not throw for non-existent file`() = runTest {
            // Should not throw
            store.clear(MemoryScope.Global)
        }

        @Test
        fun `clear only affects specified scope`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("G"))))
            store.save(MemoryScope.Chat("test"), Memory(MemoryScope.Chat("test"), listOf(Fact("C"))))

            store.clear(MemoryScope.Chat("test"))

            assertTrue(tempDir.resolve("global.md").exists())
            assertFalse(tempDir.resolve("chats/test/memory.md").exists())
        }
    }

    @Nested
    inner class ListScopesTests {

        @Test
        fun `listScopes returns empty for new store`() = runTest {
            val scopes = store.listScopes()
            assertTrue(scopes.isEmpty())
        }

        @Test
        fun `listScopes finds global scope`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("G"))))

            val scopes = store.listScopes()

            assertTrue(scopes.contains(MemoryScope.Global))
        }

        @Test
        fun `listScopes finds chat scopes`() = runTest {
            store.save(MemoryScope.Chat("chat1"), Memory(MemoryScope.Chat("chat1"), listOf(Fact("C1"))))
            store.save(MemoryScope.Chat("chat2"), Memory(MemoryScope.Chat("chat2"), listOf(Fact("C2"))))

            val scopes = store.listScopes()

            assertTrue(scopes.any { it is MemoryScope.Chat && it.chatId == "chat1" })
            assertTrue(scopes.any { it is MemoryScope.Chat && it.chatId == "chat2" })
        }

        @Test
        fun `listScopes finds user scopes`() = runTest {
            store.save(MemoryScope.User("user1"), Memory(MemoryScope.User("user1"), listOf(Fact("U"))))

            val scopes = store.listScopes()

            assertTrue(scopes.any { it is MemoryScope.User && it.userId == "user1" })
        }
    }

    @Nested
    inner class IdSanitizationTests {

        @Test
        fun `sanitizes special characters in chat id`() = runTest {
            // Chat ID with special characters that are invalid for file paths
            val scope = MemoryScope.Chat("chat+with/special:chars")
            store.save(scope, Memory(scope, listOf(Fact("Test"))))

            // Should create a sanitized directory name
            val chatsDir = tempDir.resolve("chats")
            assertTrue(chatsDir.exists())

            // Load should work with original ID
            val loaded = store.load(scope)
            assertEquals(1, loaded.facts.size)
        }

        @Test
        fun `sanitizes special characters in user id`() = runTest {
            val scope = MemoryScope.User("user@example.com")
            store.save(scope, Memory(scope, listOf(Fact("Test"))))

            // Load should work with original ID
            val loaded = store.load(scope)
            assertEquals(1, loaded.facts.size)
        }

        @Test
        fun `preserves alphanumeric and allowed characters`() = runTest {
            val scope = MemoryScope.Chat("valid-chat_123")
            store.save(scope, Memory(scope, listOf(Fact("Test"))))

            // The directory name should match (no sanitization needed)
            assertTrue(tempDir.resolve("chats/valid-chat_123/memory.md").exists())
        }
    }

    @Nested
    inner class RoundTripTests {

        @Test
        fun `facts survive save and load cycle`() = runTest {
            val original = Memory(
                scope = MemoryScope.Global,
                facts = listOf(
                    Fact("First fact with special chars: & < > \""),
                    Fact("Second fact"),
                    Fact("Third fact with unicode: 🚀"),
                ),
            )

            store.save(MemoryScope.Global, original)
            val loaded = store.load(MemoryScope.Global)

            assertEquals(3, loaded.facts.size)
            assertEquals("First fact with special chars: & < > \"", loaded.facts[0].content)
            assertEquals("Second fact", loaded.facts[1].content)
            assertEquals("Third fact with unicode: 🚀", loaded.facts[2].content)
        }
    }

    @Nested
    inner class TimestampTests {

        @Test
        fun `save writes timestamps to markdown`() = runTest {
            val timestamp = Instant.parse("2025-01-15T10:30:00Z")
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Test fact", timestamp = timestamp)),
            )

            store.save(MemoryScope.Global, memory)

            val content = tempDir.resolve("global.md").readText()
            assertTrue(content.contains("[created: 2025-01-15T10:30:00Z]"))
        }

        @Test
        fun `save writes updatedAt when present`() = runTest {
            val created = Instant.parse("2025-01-15T10:30:00Z")
            val updated = Instant.parse("2025-02-07T14:00:00Z")
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Updated fact", timestamp = created, updatedAt = updated)),
            )

            store.save(MemoryScope.Global, memory)

            val content = tempDir.resolve("global.md").readText()
            assertTrue(content.contains("[created: 2025-01-15T10:30:00Z, updated: 2025-02-07T14:00:00Z]"))
        }

        @Test
        fun `round trip preserves created timestamp`() = runTest {
            val timestamp = Instant.parse("2025-01-15T10:30:00Z")
            val original = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Fact with time", timestamp = timestamp)),
            )

            store.save(MemoryScope.Global, original)
            val loaded = store.load(MemoryScope.Global)

            assertEquals(1, loaded.facts.size)
            assertEquals("Fact with time", loaded.facts[0].content)
            assertEquals(timestamp, loaded.facts[0].timestamp)
            assertNull(loaded.facts[0].updatedAt)
        }

        @Test
        fun `round trip preserves updatedAt timestamp`() = runTest {
            val created = Instant.parse("2025-01-15T10:30:00Z")
            val updated = Instant.parse("2025-02-07T14:00:00Z")
            val original = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Updated fact", timestamp = created, updatedAt = updated)),
            )

            store.save(MemoryScope.Global, original)
            val loaded = store.load(MemoryScope.Global)

            assertEquals(1, loaded.facts.size)
            assertEquals("Updated fact", loaded.facts[0].content)
            assertEquals(created, loaded.facts[0].timestamp)
            assertEquals(updated, loaded.facts[0].updatedAt)
        }

        @Test
        fun `legacy format without timestamps still parses`() = runTest {
            val globalFile = tempDir.resolve("global.md")
            globalFile.writeText("# Memory\n\n- Old fact without timestamps\n- Another old fact")

            val loaded = store.load(MemoryScope.Global)

            assertEquals(2, loaded.facts.size)
            assertEquals("Old fact without timestamps", loaded.facts[0].content)
            assertEquals("Another old fact", loaded.facts[1].content)
            assertNull(loaded.facts[0].updatedAt)
        }

        @Test
        fun `mixed format with and without timestamps parses`() = runTest {
            val globalFile = tempDir.resolve("global.md")
            globalFile.writeText(
                "# Memory\n\n" +
                    "- Legacy fact\n" +
                    "- New fact [created: 2025-01-15T10:30:00Z]\n" +
                    "- Updated fact [created: 2025-01-10T08:00:00Z, updated: 2025-02-07T14:00:00Z]",
            )

            val loaded = store.load(MemoryScope.Global)

            assertEquals(3, loaded.facts.size)
            assertEquals("Legacy fact", loaded.facts[0].content)
            assertNull(loaded.facts[0].updatedAt)

            assertEquals("New fact", loaded.facts[1].content)
            assertEquals(Instant.parse("2025-01-15T10:30:00Z"), loaded.facts[1].timestamp)
            assertNull(loaded.facts[1].updatedAt)

            assertEquals("Updated fact", loaded.facts[2].content)
            assertEquals(Instant.parse("2025-01-10T08:00:00Z"), loaded.facts[2].timestamp)
            assertEquals(Instant.parse("2025-02-07T14:00:00Z"), loaded.facts[2].updatedAt)
        }
    }

    @Nested
    inner class UpdateFactTests {

        @Test
        fun `updateFact changes content at index`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("First"), Fact("Second"), Fact("Third"))),
            )

            store.updateFact(MemoryScope.Global, 1, "Updated second")

            val loaded = store.load(MemoryScope.Global)
            assertEquals(3, loaded.facts.size)
            assertEquals("First", loaded.facts[0].content)
            assertEquals("Updated second", loaded.facts[1].content)
            assertEquals("Third", loaded.facts[2].content)
        }

        @Test
        fun `updateFact sets updatedAt timestamp`() = runTest {
            val created = Instant.parse("2025-01-15T10:30:00Z")
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Original", timestamp = created))),
            )

            store.updateFact(MemoryScope.Global, 0, "Modified")

            val loaded = store.load(MemoryScope.Global)
            assertNotNull(loaded.facts[0].updatedAt)
        }

        @Test
        fun `updateFact preserves original timestamp`() = runTest {
            val created = Instant.parse("2025-01-15T10:30:00Z")
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Original", timestamp = created))),
            )

            store.updateFact(MemoryScope.Global, 0, "Modified")

            val loaded = store.load(MemoryScope.Global)
            assertEquals(created, loaded.facts[0].timestamp)
        }

        @Test
        fun `updateFact throws for out-of-bounds index`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Only fact"))),
            )

            assertFailsWith<IllegalArgumentException> {
                store.updateFact(MemoryScope.Global, 5, "Bad index")
            }
        }
    }

    @Nested
    inner class DeleteFactTests {

        @Test
        fun `deleteFact removes fact at index`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("First"), Fact("Second"), Fact("Third"))),
            )

            store.deleteFact(MemoryScope.Global, 1)

            val loaded = store.load(MemoryScope.Global)
            assertEquals(2, loaded.facts.size)
            assertEquals("First", loaded.facts[0].content)
            assertEquals("Third", loaded.facts[1].content)
        }

        @Test
        fun `deleteFact deletes file when last fact removed`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Only fact"))),
            )
            assertTrue(tempDir.resolve("global.md").exists())

            store.deleteFact(MemoryScope.Global, 0)

            assertFalse(tempDir.resolve("global.md").exists())
        }

        @Test
        fun `deleteFact throws for invalid index`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Only fact"))),
            )

            assertFailsWith<IllegalArgumentException> {
                store.deleteFact(MemoryScope.Global, 3)
            }
        }
    }
}
