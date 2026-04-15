package fraggle.agent

import fraggle.agent.event.AgentEvent
import fraggle.agent.loop.AgentLoopConfig
import fraggle.agent.loop.AgentOptions
import fraggle.agent.loop.runAgentLoop
import fraggle.agent.loop.runAgentLoopContinue
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.state.AgentState
import fraggle.agent.state.MutableAgentState
import fraggle.agent.state.PendingMessageQueue
import fraggle.agent.state.QueueMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Stateful agent wrapper. Owns state, manages message queues, exposes public API.
 *
 * Usage:
 * ```
 * val agent = Agent(options)
 * agent.subscribe { event -> /* handle */ }
 * agent.prompt("Hello!")
 * agent.waitForIdle()
 * println(agent.state.messages)
 * ```
 */
class Agent(private val options: AgentOptions) {
    private val _state = MutableAgentState(
        AgentState(
            systemPrompt = options.systemPrompt,
            model = options.model,
            messages = options.initialMessages.toList(),
        )
    )
    private val listeners = mutableListOf<suspend (AgentEvent) -> Unit>()
    private val steeringQueue = PendingMessageQueue(QueueMode.ALL)
    private val followUpQueue = PendingMessageQueue(QueueMode.ALL)
    private var activeJob: Job? = null

    /** Read-only snapshot of current state. */
    val state: AgentState get() = _state.snapshot()

    /** Subscribe to lifecycle events. Returns unsubscribe handle. */
    fun subscribe(listener: suspend (AgentEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Start a new prompt. Throws if already running. */
    suspend fun prompt(messages: List<AgentMessage>) {
        check(activeJob == null) { "Agent is already processing" }
        runWithLifecycle {
            val newMessages = runAgentLoop(
                prompts = messages,
                systemPrompt = _state.systemPrompt,
                messages = _state.messages,
                chatId = options.chatId,
                config = createConfig(),
                emit = ::processEvent,
            )
        }
    }

    /** Convenience: prompt with a text string. */
    suspend fun prompt(text: String, images: List<ContentPart.Image> = emptyList()) {
        val content = buildList<ContentPart> {
            add(ContentPart.Text(text))
            addAll(images)
        }
        prompt(listOf(AgentMessage.User(content)))
    }

    /** Continue/retry from current transcript. */
    suspend fun continueRun() {
        check(activeJob == null) { "Agent is already processing" }
        runWithLifecycle {
            runAgentLoopContinue(
                systemPrompt = _state.systemPrompt,
                messages = _state.messages,
                chatId = options.chatId,
                config = createConfig(),
                emit = ::processEvent,
            )
        }
    }

    /** Queue a steering message (injected during the current run). */
    fun steer(message: AgentMessage) = steeringQueue.enqueue(message)

    /** Queue a follow-up message (processed after agent would stop). */
    fun followUp(message: AgentMessage) = followUpQueue.enqueue(message)

    /** Cancel the current run. */
    fun abort() { activeJob?.cancel() }

    /** Suspend until current run completes. */
    suspend fun waitForIdle() { activeJob?.join() }

    /** Clear all state and queues. */
    fun reset() {
        _state.reset()
        steeringQueue.clear()
        followUpQueue.clear()
    }

    /**
     * Replace the agent's message history. Intended for orchestrators that
     * apply out-of-band transformations to the live conversation — most
     * notably compaction, where older messages are replaced with a summary
     * while recent messages are kept verbatim.
     *
     * Preserves [systemPrompt], [subscribe] listeners, and pending
     * steering/follow-up queues. Throws if the agent is currently running;
     * replacing messages mid-turn would race with [processEvent].
     *
     * Optionally replaces [systemPrompt] too — coding-agent orchestrators
     * re-inject the compaction summary into the system prompt rather than
     * inline, which requires updating both the message list AND the prompt
     * atomically.
     */
    fun replaceMessages(messages: List<AgentMessage>, systemPrompt: String? = null) {
        check(activeJob == null) { "Cannot replace messages while the agent is running" }
        _state.messages = messages
        if (systemPrompt != null) {
            _state.systemPrompt = systemPrompt
        }
    }

    private suspend fun runWithLifecycle(block: suspend () -> Unit) {
        _state.isStreaming = true
        _state.streamingMessage = null
        _state.errorMessage = null

        try {
            coroutineScope {
                val job = launch { block() }
                activeJob = job
                job.join()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            _state.errorMessage = e.message
        } finally {
            _state.isStreaming = false
            _state.streamingMessage = null
            _state.pendingToolCalls = emptySet()
            activeJob = null
        }
    }

    private suspend fun processEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> {
                _state.streamingMessage = event.message as? AgentMessage.Assistant
            }
            is AgentEvent.MessageUpdate -> {
                _state.streamingMessage = event.message
            }
            is AgentEvent.MessageEnd -> {
                _state.streamingMessage = null
                _state.pushMessage(event.message)
            }
            is AgentEvent.ToolExecutionStart -> {
                _state.pendingToolCalls = _state.pendingToolCalls + event.toolCallId
            }
            is AgentEvent.ToolExecutionEnd -> {
                _state.pendingToolCalls = _state.pendingToolCalls - event.toolCallId
            }
            is AgentEvent.TurnEnd -> {
                val msg = event.message
                if (msg is AgentMessage.Assistant && msg.errorMessage != null) {
                    _state.errorMessage = msg.errorMessage
                }
            }
            else -> {}
        }

        for (listener in listeners) {
            listener(event)
        }
    }

    private fun createConfig(): AgentLoopConfig = AgentLoopConfig(
        llmBridge = options.llmBridge,
        maxIterations = options.maxIterations,
        toolExecution = options.toolExecution,
        toolExecutor = options.toolExecutor,
        afterToolCall = options.afterToolCall,
        getSteeringMessages = { steeringQueue.drain() },
        getFollowUpMessages = { followUpQueue.drain() },
    )
}
