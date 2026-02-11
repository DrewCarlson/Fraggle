package fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    val nextRun: Instant?,
    val enabled: Boolean,
)
