package fraggle.memory

import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.MemoryScope as KoogMemoryScope
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.model.Fact as KoogFact
import ai.koog.agents.memory.model.SingleFact
import ai.koog.agents.memory.providers.AgentMemoryProvider

/**
 * Custom [MemorySubject] types for Fraggle's scoped memory.
 */
sealed class FraggleSubjects : MemorySubject() {
    data object Global : FraggleSubjects() {
        override val name: String = "global"
        override val promptDescription: String = "Global facts shared across all conversations"
        override val priorityLevel: Int = 0
    }

    data class ChatUser(val userId: String) : FraggleSubjects() {
        override val name: String = "user:$userId"
        override val promptDescription: String = "Personal facts about user $userId"
        override val priorityLevel: Int = 2
    }

    data class Chat(val chatId: String) : FraggleSubjects() {
        override val name: String = "chat:$chatId"
        override val promptDescription: String = "Facts specific to chat $chatId"
        override val priorityLevel: Int = 1
    }
}

/**
 * Adapter that bridges Koog's [AgentMemoryProvider] to Fraggle's [FileMemoryStore].
 *
 * Scope mapping (Koog → Fraggle):
 * - [KoogMemoryScope.Product] with name "global" → [MemoryScope.Global]
 * - [KoogMemoryScope.Feature] with id → [MemoryScope.Chat] with chatId
 * - [KoogMemoryScope.Agent] with name → [MemoryScope.User] with userId
 */
class FraggleMemoryProvider(
    private val store: MemoryStore,
) : AgentMemoryProvider {

    override suspend fun save(fact: KoogFact, subject: MemorySubject, scope: KoogMemoryScope) {
        val fraggleScope = toFraggleScope(scope, subject)
        val fraggleFact = toFraggleFact(fact)
        store.append(fraggleScope, fraggleFact)
    }

    override suspend fun load(concept: Concept, subject: MemorySubject, scope: KoogMemoryScope): List<KoogFact> {
        val fraggleScope = toFraggleScope(scope, subject)
        val memory = store.load(fraggleScope)
        return memory.facts
            .filter { it.source == concept.keyword || it.content.contains(concept.keyword, ignoreCase = true) }
            .map { toKoogFact(it, concept) }
    }

    override suspend fun loadAll(subject: MemorySubject, scope: KoogMemoryScope): List<KoogFact> {
        val fraggleScope = toFraggleScope(scope, subject)
        val memory = store.load(fraggleScope)
        return memory.facts.map { toKoogFact(it) }
    }

    override suspend fun loadByDescription(
        description: String,
        subject: MemorySubject,
        scope: KoogMemoryScope,
    ): List<KoogFact> {
        val fraggleScope = toFraggleScope(scope, subject)
        val memory = store.load(fraggleScope)
        return memory.facts
            .filter { it.content.contains(description, ignoreCase = true) }
            .map { toKoogFact(it) }
    }

    private fun toFraggleScope(scope: KoogMemoryScope, subject: MemorySubject): MemoryScope {
        return when (subject) {
            is FraggleSubjects.Global -> MemoryScope.Global
            is FraggleSubjects.ChatUser -> MemoryScope.User(subject.userId)
            is FraggleSubjects.Chat -> MemoryScope.Chat(subject.chatId)
            else -> when (scope) {
                is KoogMemoryScope.Product -> MemoryScope.Global
                is KoogMemoryScope.Feature -> MemoryScope.Chat(scope.id)
                is KoogMemoryScope.Agent -> MemoryScope.User(scope.name)
                is KoogMemoryScope.CrossProduct -> MemoryScope.Global
            }
        }
    }

    private fun toFraggleFact(fact: KoogFact): Fact {
        val value = when (fact) {
            is SingleFact -> fact.value
            else -> fact.toString()
        }
        return Fact(
            content = value,
            timestamp = kotlin.time.Instant.fromEpochMilliseconds(fact.timestamp),
            source = fact.concept.keyword,
        )
    }

    private fun toKoogFact(fact: Fact, concept: Concept? = null): KoogFact {
        val c = concept ?: Concept(
            keyword = fact.source ?: "memory",
            description = fact.content.take(100),
            factType = ai.koog.agents.memory.model.FactType.SINGLE,
        )
        return SingleFact(
            concept = c,
            timestamp = fact.timestamp.toEpochMilliseconds(),
            value = fact.content,
        )
    }
}
