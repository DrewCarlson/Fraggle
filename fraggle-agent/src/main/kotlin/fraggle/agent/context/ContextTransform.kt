package fraggle.agent.context

import fraggle.agent.message.AgentMessage

/**
 * Composable context transformation applied before each LLM call.
 * Transforms can filter, reorder, summarize, or modify the message list.
 */
fun interface ContextTransform {
    suspend fun transform(messages: List<AgentMessage>): List<AgentMessage>
}

/**
 * Compose multiple transforms into a single transform, applied in order.
 */
fun List<ContextTransform>.compose(): ContextTransform = ContextTransform { messages ->
    fold(messages) { acc, transform -> transform.transform(acc) }
}

/**
 * Combine two transforms into a pipeline.
 */
infix fun ContextTransform.then(other: ContextTransform): ContextTransform = ContextTransform { messages ->
    other.transform(this.transform(messages))
}
