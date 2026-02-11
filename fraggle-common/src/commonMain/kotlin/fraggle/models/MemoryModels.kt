package fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Response containing memory facts for a scope.
 */
@Serializable
data class MemoryResponse(
    val scope: String,
    val facts: List<FactInfo>,
)

/**
 * Information about a single memory fact.
 */
@Serializable
data class FactInfo(
    val content: String,
    val source: String?,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)

/**
 * Request to update a memory fact's content.
 */
@Serializable
data class UpdateFactRequest(val content: String)

/**
 * Information about a memory scope.
 */
@Serializable
data class MemoryScopeInfo(
    val type: String,
    val id: String,
    val label: String,
    val factCount: Int,
)

/**
 * Response listing all available memory scopes.
 */
@Serializable
data class MemoryScopesResponse(
    val scopes: List<MemoryScopeInfo>,
)
