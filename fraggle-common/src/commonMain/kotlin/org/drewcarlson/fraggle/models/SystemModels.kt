package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * System status information.
 */
@Serializable
data class SystemStatus(
    val uptime: Duration,
    val totalChats: Long,
    val connectedBridges: Int,
    val availableTools: Int,
    val scheduledTasks: Int,
    val memoryUsage: MemoryUsage,
    val uninitializedBridges: List<String> = emptyList(),
)

/**
 * Memory usage information.
 */
@Serializable
data class MemoryUsage(
    val heapUsed: Long,
    val heapMax: Long,
)
