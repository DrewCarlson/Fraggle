package fraggle.memory

import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.agents.memory.model.SingleFact
import ai.koog.agents.memory.model.MemoryScope as KoogMemoryScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class FraggleMemoryProviderTest {

    private lateinit var store: InMemoryStore
    private lateinit var provider: FraggleMemoryProvider

    @BeforeEach
    fun setup() {
        store = InMemoryStore()
        provider = FraggleMemoryProvider(store)
    }

    private fun concept(keyword: String = "test", description: String = "test concept") =
        Concept(keyword = keyword, description = description, factType = FactType.SINGLE)

    private fun singleFact(value: String, keyword: String = "test") =
        SingleFact(
            concept = concept(keyword),
            timestamp = Clock.System.now().toEpochMilliseconds(),
            value = value,
        )

    @Nested
    inner class SaveTests {

        @Test
        fun `save stores fact in global scope via Product scope`() = runTest {
            val fact = singleFact("Global fact")
            val subject = FraggleSubjects.Global
            val scope = KoogMemoryScope.Product("global")

            provider.save(fact, subject, scope)

            val memory = store.load(MemoryScope.Global)
            assertEquals(1, memory.facts.size)
            assertEquals("Global fact", memory.facts[0].content)
        }

        @Test
        fun `save stores fact in user scope via ChatUser subject`() = runTest {
            val fact = singleFact("User fact")
            val subject = FraggleSubjects.ChatUser("user123")
            val scope = KoogMemoryScope.Agent("user123")

            provider.save(fact, subject, scope)

            val memory = store.load(MemoryScope.User("user123"))
            assertEquals(1, memory.facts.size)
            assertEquals("User fact", memory.facts[0].content)
        }

        @Test
        fun `save stores fact in chat scope via Chat subject`() = runTest {
            val fact = singleFact("Chat fact")
            val subject = FraggleSubjects.Chat("chat456")
            val scope = KoogMemoryScope.Feature("chat456")

            provider.save(fact, subject, scope)

            val memory = store.load(MemoryScope.Chat("chat456"))
            assertEquals(1, memory.facts.size)
            assertEquals("Chat fact", memory.facts[0].content)
        }

        @Test
        fun `save preserves concept keyword as source`() = runTest {
            val fact = singleFact("Some fact", keyword = "conversation")
            val subject = FraggleSubjects.Global
            val scope = KoogMemoryScope.Product("global")

            provider.save(fact, subject, scope)

            val memory = store.load(MemoryScope.Global)
            assertEquals("conversation", memory.facts[0].source)
        }
    }

    @Nested
    inner class LoadAllTests {

        @Test
        fun `loadAll returns empty list for empty scope`() = runTest {
            val results = provider.loadAll(FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertTrue(results.isEmpty())
        }

        @Test
        fun `loadAll returns all facts as SingleFact objects`() = runTest {
            store.append(MemoryScope.Global, Fact("Fact one", source = "src1"))
            store.append(MemoryScope.Global, Fact("Fact two", source = "src2"))

            val results = provider.loadAll(FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertEquals(2, results.size)
            val values = results.map { (it as SingleFact).value }
            assertTrue("Fact one" in values)
            assertTrue("Fact two" in values)
        }

        @Test
        fun `loadAll isolates different scopes`() = runTest {
            store.append(MemoryScope.Global, Fact("Global"))
            store.append(MemoryScope.User("user1"), Fact("User"))

            val globalResults = provider.loadAll(FraggleSubjects.Global, KoogMemoryScope.Product("global"))
            val userResults = provider.loadAll(
                FraggleSubjects.ChatUser("user1"),
                KoogMemoryScope.Agent("user1"),
            )

            assertEquals(1, globalResults.size)
            assertEquals("Global", (globalResults[0] as SingleFact).value)
            assertEquals(1, userResults.size)
            assertEquals("User", (userResults[0] as SingleFact).value)
        }
    }

    @Nested
    inner class LoadTests {

        @Test
        fun `load filters by concept keyword matching source`() = runTest {
            store.append(MemoryScope.Global, Fact("Matching fact", source = "name"))
            store.append(MemoryScope.Global, Fact("Other fact", source = "location"))

            val concept = concept(keyword = "name")
            val results = provider.load(concept, FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertEquals(1, results.size)
            assertEquals("Matching fact", (results[0] as SingleFact).value)
        }

        @Test
        fun `load filters by concept keyword in content`() = runTest {
            store.append(MemoryScope.Global, Fact("User's name is Alice"))
            store.append(MemoryScope.Global, Fact("User lives in Paris"))

            val concept = concept(keyword = "name")
            val results = provider.load(concept, FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertEquals(1, results.size)
            assertEquals("User's name is Alice", (results[0] as SingleFact).value)
        }

        @Test
        fun `load is case insensitive for content matching`() = runTest {
            store.append(MemoryScope.Global, Fact("User's NAME is Bob"))

            val concept = concept(keyword = "name")
            val results = provider.load(concept, FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertEquals(1, results.size)
        }

        @Test
        fun `load returns empty for no matches`() = runTest {
            store.append(MemoryScope.Global, Fact("Something unrelated"))

            val concept = concept(keyword = "weather")
            val results = provider.load(concept, FraggleSubjects.Global, KoogMemoryScope.Product("global"))

            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class LoadByDescriptionTests {

        @Test
        fun `loadByDescription filters by content substring`() = runTest {
            store.append(MemoryScope.User("u1"), Fact("Alice works at Acme Corp"))
            store.append(MemoryScope.User("u1"), Fact("Alice likes cats"))

            val results = provider.loadByDescription(
                "Acme",
                FraggleSubjects.ChatUser("u1"),
                KoogMemoryScope.Agent("u1"),
            )

            assertEquals(1, results.size)
            assertEquals("Alice works at Acme Corp", (results[0] as SingleFact).value)
        }

        @Test
        fun `loadByDescription is case insensitive`() = runTest {
            store.append(MemoryScope.Global, Fact("Alice works at ACME"))

            val results = provider.loadByDescription(
                "acme",
                FraggleSubjects.Global,
                KoogMemoryScope.Product("global"),
            )

            assertEquals(1, results.size)
        }

        @Test
        fun `loadByDescription returns empty for no matches`() = runTest {
            store.append(MemoryScope.Global, Fact("Something"))

            val results = provider.loadByDescription(
                "nonexistent",
                FraggleSubjects.Global,
                KoogMemoryScope.Product("global"),
            )

            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class ScopeMappingTests {

        @Test
        fun `fallback scope mapping uses Koog scope when subject is unknown`() = runTest {
            val fact = singleFact("Test fact")

            // Using MemorySubject.Everything (not a Fraggle subject) with Product scope
            // should fall back to Global
            provider.save(fact, ai.koog.agents.memory.model.MemorySubject.Everything, KoogMemoryScope.Product("global"))

            val memory = store.load(MemoryScope.Global)
            assertEquals(1, memory.facts.size)
        }

        @Test
        fun `fallback Feature scope maps to Chat`() = runTest {
            val fact = singleFact("Feature fact")

            provider.save(fact, ai.koog.agents.memory.model.MemorySubject.Everything, KoogMemoryScope.Feature("chat789"))

            val memory = store.load(MemoryScope.Chat("chat789"))
            assertEquals(1, memory.facts.size)
        }

        @Test
        fun `fallback Agent scope maps to User`() = runTest {
            val fact = singleFact("Agent fact")

            provider.save(fact, ai.koog.agents.memory.model.MemorySubject.Everything, KoogMemoryScope.Agent("user789"))

            val memory = store.load(MemoryScope.User("user789"))
            assertEquals(1, memory.facts.size)
        }
    }

    @Nested
    inner class SaveAppendTests {

        @Test
        fun `save appends multiple facts to the same scope`() = runTest {
            val subject = FraggleSubjects.ChatUser("alice")
            val scope = KoogMemoryScope.Agent("alice")

            provider.save(singleFact("Alice is 30 years old"), subject, scope)
            provider.save(singleFact("Alice works at Acme Corp"), subject, scope)
            provider.save(singleFact("Alice lives in Paris"), subject, scope)

            val memory = store.load(MemoryScope.User("alice"))
            assertEquals(3, memory.facts.size)
        }

        @Test
        fun `save to different scopes isolates facts`() = runTest {
            provider.save(singleFact("Shared fact"), FraggleSubjects.Global, KoogMemoryScope.Product("global"))
            provider.save(singleFact("Shared fact"), FraggleSubjects.ChatUser("u1"), KoogMemoryScope.Agent("u1"))

            val global = store.load(MemoryScope.Global)
            val user = store.load(MemoryScope.User("u1"))
            assertEquals(1, global.facts.size)
            assertEquals(1, user.facts.size)
        }
    }

    @Nested
    inner class RoundTripTests {

        @Test
        fun `save and loadAll round-trip preserves content`() = runTest {
            val subject = FraggleSubjects.ChatUser("alice")
            val scope = KoogMemoryScope.Agent("alice")

            provider.save(singleFact("Alice is 30"), subject, scope)
            provider.save(singleFact("Alice likes coffee"), subject, scope)

            val loaded = provider.loadAll(subject, scope)

            assertEquals(2, loaded.size)
            val values = loaded.map { (it as SingleFact).value }.toSet()
            assertEquals(setOf("Alice is 30", "Alice likes coffee"), values)
        }
    }
}
