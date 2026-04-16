package fraggle.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single piece of message content. Messages carry an ordered list of these,
 * allowing text, thinking, tool calls, and images to interleave.
 *
 * Mirrors the content-block model used by Anthropic / OpenAI Responses.
 */
@Serializable
sealed interface ContentBlock {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    /**
     * A thinking / reasoning block. [signature] carries opaque provider-specific
     * state needed to round-trip reasoning across multi-turn calls (e.g. the
     * reasoning item id for OpenAI Responses, signature for Anthropic thinking,
     * thoughtSignature for Gemini).
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        val signature: String? = null,
        /**
         * When true, the thinking content was redacted by provider safety
         * filters; the opaque payload is stored in [signature] for continuity.
         */
        val redacted: Boolean = false,
    ) : ContentBlock

    /**
     * A tool call emitted by the assistant. [arguments] is the raw JSON
     * argument string as produced by the model, not yet parsed.
     */
    @Serializable
    @SerialName("toolCall")
    data class ToolCallBlock(
        val id: String,
        val name: String,
        val arguments: String,
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(
        val mimeType: String,
        val dataBase64: String,
    ) : ContentBlock
}
