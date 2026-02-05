package org.drewcarlson.fraggle.models

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
)
