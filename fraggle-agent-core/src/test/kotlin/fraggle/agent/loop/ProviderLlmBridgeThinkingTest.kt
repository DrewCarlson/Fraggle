package fraggle.agent.loop

import fraggle.agent.message.AgentMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import fraggle.provider.ChatRequest
import fraggle.provider.ChatResponse
import fraggle.provider.Choice
import fraggle.provider.LMStudioProvider
import fraggle.provider.Message
import fraggle.provider.ThinkingLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that [ProviderLlmBridge] forwards the current [ThinkingController.level]
 * onto every [ChatRequest] sent to the underlying [LMStudioProvider].
 */
class ProviderLlmBridgeThinkingTest {

    private fun cannedResponse() = ChatResponse(
        id = "rsp-1",
        model = "test-model",
        choices = listOf(
            Choice(index = 0, message = Message.assistant("ok"), finishReason = "stop"),
        ),
        usage = null,
    )

    @Test
    fun `null override sends request with null thinking`() = runTest {
        val provider = mockk<LMStudioProvider>()
        val captured = slot<ChatRequest>()
        coEvery { provider.chat(capture(captured)) } returns cannedResponse()

        val controller = ThinkingController()
        val bridge = ProviderLlmBridge(
            provider = provider,
            model = "test-model",
            thinkingController = controller,
        )

        bridge.call(
            systemPrompt = "system",
            messages = listOf(AgentMessage.User("hello")),
            tools = emptyList(),
        )

        coVerify { provider.chat(any()) }
        assertNull(captured.captured.thinking)
    }

    @Test
    fun `controller level lands on ChatRequest thinking field`() = runTest {
        val provider = mockk<LMStudioProvider>()
        val captured = slot<ChatRequest>()
        coEvery { provider.chat(capture(captured)) } returns cannedResponse()

        val controller = ThinkingController(ThinkingLevel.HIGH)
        val bridge = ProviderLlmBridge(
            provider = provider,
            model = "test-model",
            thinkingController = controller,
        )

        bridge.call(
            systemPrompt = "system",
            messages = listOf(AgentMessage.User("hello")),
            tools = emptyList(),
        )

        assertEquals(ThinkingLevel.HIGH, captured.captured.thinking)
    }

    @Test
    fun `controller mutation between calls is reflected in subsequent requests`() = runTest {
        val provider = mockk<LMStudioProvider>()
        val captured = mutableListOf<ChatRequest>()
        coEvery { provider.chat(capture(captured)) } returns cannedResponse()

        val controller = ThinkingController()
        val bridge = ProviderLlmBridge(
            provider = provider,
            model = "test-model",
            thinkingController = controller,
        )

        bridge.call("system", listOf(AgentMessage.User("a")), emptyList())
        controller.level = ThinkingLevel.OFF
        bridge.call("system", listOf(AgentMessage.User("b")), emptyList())
        controller.level = ThinkingLevel.ON
        bridge.call("system", listOf(AgentMessage.User("c")), emptyList())

        assertEquals(3, captured.size)
        assertNull(captured[0].thinking)
        assertEquals(ThinkingLevel.OFF, captured[1].thinking)
        assertEquals(ThinkingLevel.ON, captured[2].thinking)
    }
}
