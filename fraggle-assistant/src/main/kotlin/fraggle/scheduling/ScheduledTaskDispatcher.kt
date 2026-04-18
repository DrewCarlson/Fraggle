package fraggle.scheduling

import fraggle.chat.ChatBridgeManager
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Handles the side effects of a scheduled task firing: emits a lifecycle event on
 * [eventBus] and injects a framed synthetic message into the task's chat via
 * [bridgeManager] so the assistant re-enters an agent turn to execute the action.
 *
 * Pulling this out of the DI module keeps the `TaskScheduler` callback a one-liner
 * and makes the framing logic unit-testable without spinning up a scheduler.
 */
class ScheduledTaskDispatcher(
    private val bridgeManager: ChatBridgeManager,
    private val eventBus: EventBus,
) {
    private val logger = LoggerFactory.getLogger(ScheduledTaskDispatcher::class.java)

    suspend fun dispatch(task: ScheduledTask) {
        logger.info("Task triggered: {} - {}", task.name, task.action)
        eventBus.emit(
            FraggleEvent.TaskTriggered(
                timestamp = Clock.System.now(),
                taskId = task.id,
                taskName = task.name,
                chatId = task.chatId,
            )
        )

        if (!bridgeManager.hasConnectedBridge()) {
            logger.warn("Cannot inject task action: No chat bridge connected")
            return
        }

        try {
            bridgeManager.injectMessage(
                qualifiedChatId = task.chatId,
                text = frameAction(task),
                senderName = "Scheduled Task: ${task.name}",
                isScheduled = true,
                defaultSkill = task.skill,
            )
            logger.info("Task action injected for {}: {}", task.chatId, task.action)
        } catch (e: Exception) {
            logger.error("Failed to inject task action: ${e.message}", e)
        }
    }

    private fun frameAction(task: ScheduledTask): String = buildString {
        appendLine("[Scheduled task: ${task.name}]")
        append("Perform the following action now. Do not skip, summarize, or ")
        appendLine("assume a result before executing it.")
        appendLine()
        appendLine("Action:")
        appendLine(task.action)
        appendLine()
        append("After you have executed the action and inspected the real result, ")
        append("decide: if the result contains information the user needs to ")
        append("see, reply with that information; otherwise call skip_reply to ")
        append("end the turn silently.")
    }
}
