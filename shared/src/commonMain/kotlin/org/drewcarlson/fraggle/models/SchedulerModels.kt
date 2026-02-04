package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Information about a scheduled task.
 */
@Serializable
data class ScheduledTaskInfo(
    val id: String,
    val name: String,
    val chatId: String,
    val action: String,
    val schedule: String,
    val nextRun: Long?,
    val enabled: Boolean,
)
