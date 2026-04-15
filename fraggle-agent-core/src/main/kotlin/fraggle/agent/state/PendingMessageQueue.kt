package fraggle.agent.state

import fraggle.agent.message.AgentMessage

/**
 * Thread-safe queue for injecting messages into the agent loop.
 * Used for steering (mid-turn injection) and follow-up (post-turn injection).
 */
class PendingMessageQueue(private val mode: QueueMode = QueueMode.ONE_AT_A_TIME) {
    private val queue = mutableListOf<AgentMessage>()

    fun enqueue(message: AgentMessage) {
        synchronized(queue) { queue.add(message) }
    }

    fun hasItems(): Boolean = synchronized(queue) { queue.isNotEmpty() }

    fun clear() {
        synchronized(queue) { queue.clear() }
    }

    fun drain(): List<AgentMessage> = synchronized(queue) {
        if (queue.isEmpty()) return emptyList()
        when (mode) {
            QueueMode.ALL -> queue.toList().also { queue.clear() }
            QueueMode.ONE_AT_A_TIME -> listOf(queue.removeFirst())
        }
    }
}

enum class QueueMode { ALL, ONE_AT_A_TIME }
