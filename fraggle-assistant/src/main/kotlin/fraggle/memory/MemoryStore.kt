package fraggle.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Hierarchical memory storage interface.
 * Supports global, chat-scoped, and user-scoped memory.
 */
interface MemoryStore {
    /**
     * Load memory for a given scope.
     */
    suspend fun load(scope: MemoryScope): Memory

    /**
     * Save memory for a given scope.
     */
    suspend fun save(scope: MemoryScope, memory: Memory)

    /**
     * Append a fact to memory for a given scope.
     */
    suspend fun append(scope: MemoryScope, fact: Fact)

    /**
     * Update the content of a fact at the given index.
     */
    suspend fun updateFact(scope: MemoryScope, index: Int, content: String)

    /**
     * Delete the fact at the given index.
     */
    suspend fun deleteFact(scope: MemoryScope, index: Int)

    /**
     * Clear memory for a given scope.
     */
    suspend fun clear(scope: MemoryScope)

    /**
     * List all available memory scopes.
     */
    suspend fun listScopes(): List<MemoryScope>
}

/**
 * Memory scope definitions.
 */
sealed class MemoryScope {
    /**
     * Global memory, shared across all conversations.
     */
    data object Global : MemoryScope() {
        override fun toString() = "global"
    }

    /**
     * Per-chat memory.
     */
    data class Chat(val chatId: String) : MemoryScope() {
        override fun toString() = "chat:$chatId"
    }

    /**
     * Per-user memory.
     */
    data class User(val userId: String) : MemoryScope() {
        override fun toString() = "user:$userId"
    }
}

/**
 * Memory container with facts.
 */
data class Memory(
    val scope: MemoryScope,
    val facts: List<Fact>,
    val lastUpdated: Instant? = null,
) {
    /**
     * Convert memory to a string for inclusion in prompts.
     */
    fun toPromptString(): String {
        if (facts.isEmpty()) return ""

        val scopeLabel = when (scope) {
            is MemoryScope.Global -> "Global Memory"
            is MemoryScope.Chat -> "Chat Memory"
            is MemoryScope.User -> "User Memory"
        }

        return buildString {
            appendLine("## $scopeLabel")
            appendLine()
            for (fact in facts) {
                appendLine("- ${fact.content}")
            }
        }
    }
}

/**
 * A single memory fact.
 */
data class Fact(
    val content: String,
    val timestamp: Instant = Clock.System.now(),
    val updatedAt: Instant? = null,
    val source: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * File-based implementation of MemoryStore.
 * Stores memory as markdown files for human readability.
 *
 * Directory structure:
 * - {baseDir}/global.md
 * - {baseDir}/chats/{chatId}/memory.md
 * - {baseDir}/users/{userId}/memory.md
 */
class FileMemoryStore(
    private val baseDir: Path,
) : MemoryStore {
    private val mutex = Mutex()

    init {
        // Ensure base directory exists
        if (!baseDir.exists()) {
            baseDir.createDirectories()
        }
    }

    override suspend fun load(scope: MemoryScope): Memory = withContext(Dispatchers.IO) {
        val path = getPath(scope)

        if (!path.exists()) {
            return@withContext Memory(scope, emptyList())
        }

        val content = path.readText()
        val facts = parseMemoryFile(content)

        Memory(
            scope = scope,
            facts = facts,
            lastUpdated = path.getLastModifiedTime().toInstant().toKotlinInstant(),
        )
    }

    override suspend fun save(scope: MemoryScope, memory: Memory) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = getPath(scope)
            path.parent?.createDirectories()

            val content = formatMemoryFile(memory.facts)
            path.writeText(content)
        }
    }

    override suspend fun append(scope: MemoryScope, fact: Fact) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = getPath(scope)
            path.parent?.createDirectories()

            val line = formatFact(fact)

            if (path.exists()) {
                path.appendText("\n$line")
            } else {
                path.writeText("# Memory\n\n$line")
            }
        }
    }

    override suspend fun updateFact(scope: MemoryScope, index: Int, content: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = getPath(scope)
            val facts = if (path.exists()) parseMemoryFile(path.readText()).toMutableList() else mutableListOf()
            require(index in facts.indices) { "Fact index $index out of bounds (size=${facts.size})" }
            facts[index] = facts[index].copy(content = content, updatedAt = Clock.System.now())
            path.parent?.createDirectories()
            path.writeText(formatMemoryFile(facts))
        }
    }

    override suspend fun deleteFact(scope: MemoryScope, index: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = getPath(scope)
            val facts = if (path.exists()) parseMemoryFile(path.readText()).toMutableList() else mutableListOf()
            require(index in facts.indices) { "Fact index $index out of bounds (size=${facts.size})" }
            facts.removeAt(index)
            if (facts.isEmpty()) {
                if (path.exists()) path.deleteExisting()
            } else {
                path.writeText(formatMemoryFile(facts))
            }
        }
    }

    override suspend fun clear(scope: MemoryScope) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = getPath(scope)
            if (path.exists()) {
                path.deleteExisting()
            }
        }
    }

    override suspend fun listScopes(): List<MemoryScope> = withContext(Dispatchers.IO) {
        val scopes = mutableListOf<MemoryScope>()

        // Check global
        if (getPath(MemoryScope.Global).exists()) {
            scopes.add(MemoryScope.Global)
        }

        // Check chats
        val chatsDir = baseDir.resolve("chats")
        if (chatsDir.exists() && chatsDir.isDirectory()) {
            Files.list(chatsDir).use { stream ->
                stream.forEach { chatDir ->
                    if (chatDir.isDirectory() && chatDir.resolve("memory.md").exists()) {
                        scopes.add(MemoryScope.Chat(chatDir.name))
                    }
                }
            }
        }

        // Check users
        val usersDir = baseDir.resolve("users")
        if (usersDir.exists() && usersDir.isDirectory()) {
            Files.list(usersDir).use { stream ->
                stream.forEach { userDir ->
                    if (userDir.isDirectory() && userDir.resolve("memory.md").exists()) {
                        scopes.add(MemoryScope.User(userDir.name))
                    }
                }
            }
        }

        scopes
    }

    private fun getPath(scope: MemoryScope): Path {
        return when (scope) {
            is MemoryScope.Global -> baseDir.resolve("global.md")
            is MemoryScope.Chat -> baseDir.resolve("chats").resolve(sanitizeId(scope.chatId)).resolve("memory.md")
            is MemoryScope.User -> baseDir.resolve("users").resolve(sanitizeId(scope.userId)).resolve("memory.md")
        }
    }

    private fun sanitizeId(id: String): String {
        // Replace unsafe characters for filesystem
        return id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }

    private val metadataRegex = Regex("""\s*\[created:\s*([^\],]+)(?:,\s*updated:\s*([^\]]+))?\]\s*$""")

    private fun parseMemoryFile(content: String): List<Fact> {
        val facts = mutableListOf<Fact>()

        for (line in content.lines()) {
            val trimmed = line.trim()

            // Parse lines starting with "- " as facts
            if (trimmed.startsWith("- ")) {
                val rawContent = trimmed.removePrefix("- ").trim()
                if (rawContent.isEmpty()) continue

                val match = metadataRegex.find(rawContent)
                if (match != null) {
                    val factText = rawContent.substring(0, match.range.first).trim()
                    val createdStr = match.groupValues[1].trim()
                    val updatedStr = match.groupValues[2].trim().ifEmpty { null }

                    val created = runCatching { Instant.parse(createdStr) }.getOrElse { Clock.System.now() }
                    val updated = updatedStr?.let { runCatching { Instant.parse(it) }.getOrNull() }

                    facts.add(Fact(content = factText, timestamp = created, updatedAt = updated))
                } else {
                    // Legacy format (no timestamps)
                    facts.add(Fact(content = rawContent))
                }
            }
        }

        return facts
    }

    private fun formatMemoryFile(facts: List<Fact>): String {
        return buildString {
            appendLine("# Memory")
            appendLine()
            for (fact in facts) {
                appendLine(formatFact(fact))
            }
        }
    }

    private fun formatFact(fact: Fact): String {
        val meta = buildString {
            append("[created: ${fact.timestamp}")
            if (fact.updatedAt != null) {
                append(", updated: ${fact.updatedAt}")
            }
            append("]")
        }
        return "- ${fact.content} $meta"
    }
}

/**
 * In-memory implementation for testing.
 */
class InMemoryStore : MemoryStore {
    private val storage = mutableMapOf<String, Memory>()
    private val mutex = Mutex()

    override suspend fun load(scope: MemoryScope): Memory {
        return storage[scope.toString()] ?: Memory(scope, emptyList())
    }

    override suspend fun save(scope: MemoryScope, memory: Memory) {
        mutex.withLock {
            storage[scope.toString()] = memory
        }
    }

    override suspend fun append(scope: MemoryScope, fact: Fact) {
        mutex.withLock {
            val existing = storage[scope.toString()] ?: Memory(scope, emptyList())
            storage[scope.toString()] = existing.copy(
                facts = existing.facts + fact,
                lastUpdated = Clock.System.now(),
            )
        }
    }

    override suspend fun updateFact(scope: MemoryScope, index: Int, content: String) {
        mutex.withLock {
            val existing = storage[scope.toString()] ?: Memory(scope, emptyList())
            require(index in existing.facts.indices) { "Fact index $index out of bounds (size=${existing.facts.size})" }
            val updatedFacts = existing.facts.toMutableList()
            updatedFacts[index] = updatedFacts[index].copy(content = content, updatedAt = Clock.System.now())
            storage[scope.toString()] = existing.copy(facts = updatedFacts, lastUpdated = Clock.System.now())
        }
    }

    override suspend fun deleteFact(scope: MemoryScope, index: Int) {
        mutex.withLock {
            val existing = storage[scope.toString()] ?: Memory(scope, emptyList())
            require(index in existing.facts.indices) { "Fact index $index out of bounds (size=${existing.facts.size})" }
            val updatedFacts = existing.facts.toMutableList()
            updatedFacts.removeAt(index)
            if (updatedFacts.isEmpty()) {
                storage.remove(scope.toString())
            } else {
                storage[scope.toString()] = existing.copy(facts = updatedFacts, lastUpdated = Clock.System.now())
            }
        }
    }

    override suspend fun clear(scope: MemoryScope) {
        mutex.withLock {
            storage.remove(scope.toString())
        }
    }

    override suspend fun listScopes(): List<MemoryScope> {
        return storage.keys.mapNotNull { key ->
            when {
                key == "global" -> MemoryScope.Global
                key.startsWith("chat:") -> MemoryScope.Chat(key.removePrefix("chat:"))
                key.startsWith("user:") -> MemoryScope.User(key.removePrefix("user:"))
                else -> null
            }
        }
    }
}
