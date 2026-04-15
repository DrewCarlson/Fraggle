package fraggle.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Describes the capabilities and characteristics of a chat platform.
 */
data class ChatPlatform(
    /**
     * Human-readable name of the platform (e.g., "Signal", "Discord", "WhatsApp")
     */
    val name: String,

    /**
     * Whether the platform supports markdown formatting.
     */
    val supportsMarkdown: Boolean = false,

    /**
     * Whether the platform supports inline images via URLs/markdown.
     * If false, images must be sent as attachments.
     */
    val supportsInlineImages: Boolean = false,

    /**
     * Whether the platform supports file attachments.
     */
    val supportsAttachments: Boolean = true,

    /**
     * Whether the platform supports message reactions.
     */
    val supportsReactions: Boolean = false,

    /**
     * Maximum message length (0 = unlimited).
     */
    val maxMessageLength: Int = 0,

    /**
     * Whether an activate action should always be available in the UI.
     * When true, users can access setup at any time (e.g., to get OAuth links).
     * When false, setup is only shown when the bridge is not initialized.
     */
    val persistentActivation: Boolean = false,

    /**
     * Additional notes about the platform for the AI to consider.
     */
    val notes: String? = null,
)

/**
 * Abstract interface for chat platform integrations.
 * Implementations can wrap Signal, WhatsApp, Telegram, etc.
 */
interface ChatBridge {
    /**
     * The platform this bridge connects to.
     */
    val platform: ChatPlatform

    /**
     * Connect to the chat platform.
     */
    suspend fun connect()

    /**
     * Disconnect from the chat platform.
     */
    suspend fun disconnect()

    /**
     * Stream of incoming messages.
     */
    fun messages(): Flow<IncomingMessage>

    /**
     * Send a message to a chat.
     */
    suspend fun send(chatId: String, message: OutgoingMessage)

    /**
     * Set typing indicator for a chat.
     */
    suspend fun setTyping(chatId: String, typing: Boolean)

    /**
     * Get information about a chat.
     */
    suspend fun getChatInfo(chatId: String): ChatInfo?

    /**
     * Check if the bridge is connected.
     */
    fun isConnected(): Boolean
}

/**
 * Incoming message from a chat platform.
 */
data class IncomingMessage(
    val id: String,
    val chatId: String,
    val sender: Sender,
    val content: MessageContent,
    val timestamp: Instant,
    val replyTo: String? = null,
    val mentions: List<String> = emptyList(),
    val imageAttachments: List<ImageAttachment> = emptyList(),
)

/**
 * An image attachment received from a chat platform.
 * Stored as raw bytes for passing to vision-capable LLMs.
 */
data class ImageAttachment(
    val data: ByteArray,
    val mimeType: String,
    val filename: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageAttachment) return false
        return data.contentEquals(other.data) && mimeType == other.mimeType && filename == other.filename
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        return result
    }
}

/**
 * Sender information.
 */
data class Sender(
    val id: String,
    val name: String? = null,
    val isBot: Boolean = false,
)

/**
 * Message content types.
 */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Image(val data: ByteArray, val mimeType: String, val caption: String? = null) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType && caption == other.caption
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (caption?.hashCode() ?: 0)
            return result
        }
    }

    data class File(val data: ByteArray, val filename: String, val mimeType: String? = null) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is File) return false
            return data.contentEquals(other.data) && filename == other.filename && mimeType == other.mimeType
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    data class Audio(val data: ByteArray, val duration: Int? = null) : MessageContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Audio) return false
            return data.contentEquals(other.data) && duration == other.duration
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + (duration ?: 0)
            return result
        }
    }

    data class Sticker(val id: String, val emoji: String? = null) : MessageContent()
    data class Reaction(val emoji: String, val targetMessageId: String) : MessageContent()
}

/**
 * Outgoing message to send to a chat.
 */
sealed class OutgoingMessage {
    data class Text(
        val text: String,
        val replyTo: String? = null,
        val mentions: List<String> = emptyList(),
    ) : OutgoingMessage()

    data class Image(
        val data: ByteArray,
        val mimeType: String,
        val caption: String? = null,
        val replyTo: String? = null,
    ) : OutgoingMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType &&
                caption == other.caption && replyTo == other.replyTo
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (caption?.hashCode() ?: 0)
            result = 31 * result + (replyTo?.hashCode() ?: 0)
            return result
        }
    }

    data class File(
        val data: ByteArray,
        val filename: String,
        val mimeType: String? = null,
        val replyTo: String? = null,
    ) : OutgoingMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is File) return false
            return data.contentEquals(other.data) && filename == other.filename &&
                mimeType == other.mimeType && replyTo == other.replyTo
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + (replyTo?.hashCode() ?: 0)
            return result
        }
    }

    data class Reaction(
        val emoji: String,
        val targetMessageId: String,
    ) : OutgoingMessage()
}

/**
 * Information about a chat.
 */
@Serializable
data class ChatInfo(
    val id: String,
    val name: String? = null,
    val isGroup: Boolean = false,
    val memberCount: Int? = null,
)
