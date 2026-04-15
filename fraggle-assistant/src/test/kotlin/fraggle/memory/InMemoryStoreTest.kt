package fraggle.memory

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryStoreTest {

    private lateinit var store: InMemoryStore

    @BeforeEach
    fun setup() {
        store = InMemoryStore()
    }

    @Nested
    inner class LoadTests {

        @Test
        fun `load returns empty memory for non-existent scope`() = runTest {
            val memory = store.load(MemoryScope.Global)

            assertEquals(MemoryScope.Global, memory.scope)
            assertTrue(memory.facts.isEmpty())
        }

        @Test
        fun `load returns saved memory`() = runTest {
            val originalMemory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Test fact")),
            )
            store.save(MemoryScope.Global, originalMemory)

            val loaded = store.load(MemoryScope.Global)

            assertEquals(1, loaded.facts.size)
            assertEquals("Test fact", loaded.facts[0].content)
        }

        @Test
        fun `load isolates chat scopes`() = runTest {
            store.save(
                MemoryScope.Chat("chat1"),
                Memory(MemoryScope.Chat("chat1"), listOf(Fact("Chat 1 fact")))
            )
            store.save(
                MemoryScope.Chat("chat2"),
                Memory(MemoryScope.Chat("chat2"), listOf(Fact("Chat 2 fact")))
            )

            val chat1 = store.load(MemoryScope.Chat("chat1"))
            val chat2 = store.load(MemoryScope.Chat("chat2"))

            assertEquals("Chat 1 fact", chat1.facts[0].content)
            assertEquals("Chat 2 fact", chat2.facts[0].content)
        }

        @Test
        fun `load isolates user scopes`() = runTest {
            store.save(
                MemoryScope.User("user1"),
                Memory(MemoryScope.User("user1"), listOf(Fact("User 1 fact")))
            )
            store.save(
                MemoryScope.User("user2"),
                Memory(MemoryScope.User("user2"), listOf(Fact("User 2 fact")))
            )

            val user1 = store.load(MemoryScope.User("user1"))
            val user2 = store.load(MemoryScope.User("user2"))

            assertEquals("User 1 fact", user1.facts[0].content)
            assertEquals("User 2 fact", user2.facts[0].content)
        }

        @Test
        fun `load isolates different scope types`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Global")))
            )
            store.save(
                MemoryScope.Chat("test"),
                Memory(MemoryScope.Chat("test"), listOf(Fact("Chat")))
            )
            store.save(
                MemoryScope.User("test"),
                Memory(MemoryScope.User("test"), listOf(Fact("User")))
            )

            assertEquals("Global", store.load(MemoryScope.Global).facts[0].content)
            assertEquals("Chat", store.load(MemoryScope.Chat("test")).facts[0].content)
            assertEquals("User", store.load(MemoryScope.User("test")).facts[0].content)
        }
    }

    @Nested
    inner class SaveTests {

        @Test
        fun `save stores memory`() = runTest {
            val memory = Memory(
                scope = MemoryScope.Global,
                facts = listOf(Fact("Saved fact")),
            )

            store.save(MemoryScope.Global, memory)

            val loaded = store.load(MemoryScope.Global)
            assertEquals(1, loaded.facts.size)
        }

        @Test
        fun `save overwrites previous memory`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Original")))
            )
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Replacement")))
            )

            val loaded = store.load(MemoryScope.Global)
            assertEquals(1, loaded.facts.size)
            assertEquals("Replacement", loaded.facts[0].content)
        }
    }

    @Nested
    inner class AppendTests {

        @Test
        fun `append adds fact to empty memory`() = runTest {
            store.append(MemoryScope.Global, Fact("First fact"))

            val loaded = store.load(MemoryScope.Global)
            assertEquals(1, loaded.facts.size)
            assertEquals("First fact", loaded.facts[0].content)
        }

        @Test
        fun `append adds fact to existing memory`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Existing")))
            )

            store.append(MemoryScope.Global, Fact("Appended"))

            val loaded = store.load(MemoryScope.Global)
            assertEquals(2, loaded.facts.size)
            assertEquals("Existing", loaded.facts[0].content)
            assertEquals("Appended", loaded.facts[1].content)
        }

        @Test
        fun `append updates lastUpdated`() = runTest {
            store.append(MemoryScope.Global, Fact("Test"))

            val loaded = store.load(MemoryScope.Global)
            assertNotNull(loaded.lastUpdated)
        }

        @Test
        fun `multiple appends accumulate facts`() = runTest {
            store.append(MemoryScope.Global, Fact("First"))
            store.append(MemoryScope.Global, Fact("Second"))
            store.append(MemoryScope.Global, Fact("Third"))

            val loaded = store.load(MemoryScope.Global)
            assertEquals(3, loaded.facts.size)
        }
    }

    @Nested
    inner class ClearTests {

        @Test
        fun `clear removes memory for scope`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("To be cleared")))
            )

            store.clear(MemoryScope.Global)

            val loaded = store.load(MemoryScope.Global)
            assertTrue(loaded.facts.isEmpty())
        }

        @Test
        fun `clear on non-existent scope does not throw`() = runTest {
            // Should not throw
            store.clear(MemoryScope.Chat("nonexistent"))
        }

        @Test
        fun `clear only affects specified scope`() = runTest {
            store.save(
                MemoryScope.Global,
                Memory(MemoryScope.Global, listOf(Fact("Global")))
            )
            store.save(
                MemoryScope.Chat("test"),
                Memory(MemoryScope.Chat("test"), listOf(Fact("Chat")))
            )

            store.clear(MemoryScope.Chat("test"))

            assertEquals(1, store.load(MemoryScope.Global).facts.size)
            assertTrue(store.load(MemoryScope.Chat("test")).facts.isEmpty())
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
        fun `listScopes returns global scope`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("Test"))))

            val scopes = store.listScopes()

            assertTrue(scopes.contains(MemoryScope.Global))
        }

        @Test
        fun `listScopes returns chat scopes`() = runTest {
            store.save(
                MemoryScope.Chat("chat123"),
                Memory(MemoryScope.Chat("chat123"), listOf(Fact("Test")))
            )

            val scopes = store.listScopes()

            assertTrue(scopes.any { it is MemoryScope.Chat && it.chatId == "chat123" })
        }

        @Test
        fun `listScopes returns user scopes`() = runTest {
            store.save(
                MemoryScope.User("user456"),
                Memory(MemoryScope.User("user456"), listOf(Fact("Test")))
            )

            val scopes = store.listScopes()

            assertTrue(scopes.any { it is MemoryScope.User && it.userId == "user456" })
        }

        @Test
        fun `listScopes returns all scope types`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("G"))))
            store.save(MemoryScope.Chat("c1"), Memory(MemoryScope.Chat("c1"), listOf(Fact("C"))))
            store.save(MemoryScope.User("u1"), Memory(MemoryScope.User("u1"), listOf(Fact("U"))))

            val scopes = store.listScopes()

            assertEquals(3, scopes.size)
            assertTrue(scopes.contains(MemoryScope.Global))
            assertTrue(scopes.any { it is MemoryScope.Chat })
            assertTrue(scopes.any { it is MemoryScope.User })
        }

        @Test
        fun `listScopes does not include cleared scopes`() = runTest {
            store.save(MemoryScope.Global, Memory(MemoryScope.Global, listOf(Fact("Test"))))
            store.clear(MemoryScope.Global)

            val scopes = store.listScopes()

            assertTrue(scopes.isEmpty())
        }
    }
}
