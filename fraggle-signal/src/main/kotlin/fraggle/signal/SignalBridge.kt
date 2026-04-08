package fraggle.signal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import fraggle.chat.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * ChatBridge implementation for Signal.
 * Uses signal-cli in JSON-RPC mode for communication.
 */
class SignalBridge(
    private val config: SignalConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : ChatBridge {
    private val logger = LoggerFactory.getLogger(SignalBridge::class.java)
    private val signalCli = SignalCli(config, scope)

    override val platform = ChatPlatform(
        name = "Signal",
        supportsMarkdown = true, // We support a subset via our formatter
        supportsInlineImages = false,
        supportsAttachments = true,
        supportsReactions = true,
        maxMessageLength = 0, // Signal has no hard limit
        notes = """Signal is a secure messaging app with basic text formatting support.

SUPPORTED FORMATTING (use these in your messages):
- **bold text** → displays as bold
- *italic text* → displays as italic
- ~~strikethrough~~ → displays as strikethrough
- ||spoiler text|| → displays as a spoiler (hidden until tapped)
- `monospace` → displays in monospace font

SENDING IMAGES:
- To include an image, use: [[image:URL]] (e.g., [[image:https://example.com/photo.jpg]])
- The image will be attached to your message automatically
- Only ONE image can be sent per message on Signal
- For screenshots, use the screenshot_page tool

IMPORTANT LIMITATIONS:
- Do NOT include raw image URLs or markdown image syntax like ![](url) - these will not display
- Do NOT use markdown links like [text](url) - just include the URL directly
- Do NOT use headers (#), markdown tables, bullet lists, or code blocks (```). Use plain formatting instead."""
    )

    override suspend fun connect() {
        logger.info("Connecting to Signal...")
        signalCli.start()
        logger.info("Connected to Signal as ${config.phoneNumber}")

        // Set the profile name so recipients see a name instead of "Unknown"
        signalCli.updateProfile(config.profileName)
    }

    override suspend fun disconnect() {
        logger.info("Disconnecting from Signal...")
        signalCli.stop()
        logger.info("Disconnected from Signal")
    }

    override fun messages(): Flow<IncomingMessage> {
        return signalCli.messages().map { signalMessage ->
            signalMessage.toIncomingMessage()
        }
    }

    override suspend fun send(chatId: String, message: OutgoingMessage) {
        when (message) {
            is OutgoingMessage.Text -> {
                val (recipient, groupId) = parseChatId(chatId)

                // Parse and apply text formatting
                val (plainText, textStyles) = TextFormatter.formatForCli(message.text)

                signalCli.send(
                    recipient = recipient,
                    message = plainText,
                    groupId = groupId,
                    textStyles = textStyles.takeIf { it.isNotEmpty() },
                )
            }

            is OutgoingMessage.Reaction -> {
                val (recipient, groupId) = parseChatId(chatId)
                // Note: Reactions require the target message author and timestamp
                // This is a simplified version - full implementation would need message tracking
                logger.warn("Reaction sending requires message tracking - not fully implemented")
            }

            is OutgoingMessage.Image -> {
                val (recipient, groupId) = parseChatId(chatId)

                // Write image data to a temp file
                val tempFile = withContext(Dispatchers.IO) {
                    val extension = when {
                        message.mimeType.contains("png") -> ".png"
                        message.mimeType.contains("gif") -> ".gif"
                        message.mimeType.contains("webp") -> ".webp"
                        else -> ".jpg"
                    }
                    val tempPath = Files.createTempFile("fraggle-image-", extension)
                    tempPath.writeBytes(message.data)
                    tempPath.toAbsolutePath().toString()
                }

                logger.info("Sending image attachment: $tempFile")

                // Format caption if present
                val (captionText, captionStyles) = message.caption?.let {
                    TextFormatter.formatForCli(it)
                } ?: (null to emptyList())

                signalCli.sendWithAttachments(
                    recipient = recipient,
                    message = captionText,
                    attachments = listOf(tempFile),
                    groupId = groupId,
                    textStyles = captionStyles.takeIf { it.isNotEmpty() },
                )
            }

            is OutgoingMessage.File -> {
                val (recipient, groupId) = parseChatId(chatId)

                // Write file data to a temp file
                val tempFile = withContext(Dispatchers.IO) {
                    val tempPath = Files.createTempFile("fraggle-file-", "-${message.filename}")
                    tempPath.writeBytes(message.data)
                    tempPath.toAbsolutePath().toString()
                }

                logger.info("Sending file attachment: $tempFile")
                signalCli.sendWithAttachments(
                    recipient = recipient,
                    message = null,
                    attachments = listOf(tempFile),
                    groupId = groupId,
                )
            }
        }
    }

    override suspend fun setTyping(chatId: String, typing: Boolean) {
        if (!config.showTypingIndicator) return

        val (recipient, groupId) = parseChatId(chatId)
        signalCli.sendTyping(
            recipient = recipient,
            groupId = groupId,
            stop = !typing,
        )
    }

    override suspend fun getChatInfo(chatId: String): ChatInfo? {
        val (_, groupId) = parseChatId(chatId)

        return if (groupId != null) {
            val group = signalCli.getGroup(groupId)
            group?.let {
                ChatInfo(
                    id = chatId,
                    name = it.name,
                    isGroup = true,
                    memberCount = it.members.size,
                )
            }
        } else {
            // Direct message - limited info available
            ChatInfo(
                id = chatId,
                name = null,
                isGroup = false,
            )
        }
    }

    override fun isConnected(): Boolean {
        val state = signalCli.connectionState.value
        return state is ConnectionState.Connected || state is ConnectionState.Reconnecting
    }

    /**
     * Get the current connection state.
     */
    val connectionState: StateFlow<ConnectionState>
        get() = signalCli.connectionState

    /**
     * Check if the Signal account is properly initialized and authenticated.
     * Uses the RPC connection if available.
     * Returns null if the check cannot be performed (e.g., not connected).
     */
    suspend fun checkAccountInitialized(): Boolean? {
        if (!isConnected()) {
            return null
        }
        return try {
            signalCli.getUserStatus()
        } catch (e: Exception) {
            logger.warn("Failed to check account status: ${e.message}")
            null
        }
    }

    /**
     * Parse a chat ID into recipient and optional group ID.
     * Format: "group:base64groupid" for groups, or phone number for direct.
     */
    private fun parseChatId(chatId: String): Pair<String, String?> {
        return if (chatId.startsWith("group:")) {
            val groupId = chatId.removePrefix("group:")
            // For groups, recipient is ignored but we need to provide something
            config.phoneNumber to groupId
        } else {
            chatId to null
        }
    }

    private fun SignalMessage.toIncomingMessage(): IncomingMessage {
        val chatId = if (isGroupMessage && groupId != null) {
            "group:$groupId"
        } else {
            source
        }

        // Read image attachments from signal-cli's data directory
        val imageAttachments = attachments
            .filter { it.contentType.startsWith("image/") }
            .mapNotNull { attachment ->
                readSignalAttachment(attachment)
            }

        return IncomingMessage(
            id = "$source-${timestamp.toEpochMilliseconds()}",
            chatId = chatId,
            sender = Sender(
                id = source,
                name = sourceName,
            ),
            content = MessageContent.Text(message),
            timestamp = timestamp,
            imageAttachments = imageAttachments,
        )
    }

    /**
     * Read an attachment file from signal-cli's data directory.
     * Prefers the `file` path provided by signal-cli's JSON-RPC response,
     * falling back to searching the attachments directory by ID.
     */
    private fun readSignalAttachment(attachment: SignalAttachment): ImageAttachment? {
        val attachmentFile = resolveAttachmentFile(attachment)
        if (attachmentFile == null) {
            logger.warn(
                "Could not locate attachment file (id={}, file={}, contentType={})",
                attachment.id, attachment.file, attachment.contentType,
            )
            return null
        }

        return try {
            ImageAttachment(
                data = attachmentFile.readBytes(),
                mimeType = attachment.contentType,
                filename = attachment.filename,
            )
        } catch (e: Exception) {
            logger.warn("Failed to read attachment file $attachmentFile: ${e.message}")
            null
        }
    }

    /**
     * Resolve the attachment file path. Tries the direct `file` path from signal-cli first,
     * then falls back to searching the attachments directory by ID prefix.
     */
    private fun resolveAttachmentFile(attachment: SignalAttachment): Path? {
        // Prefer the direct file path provided by signal-cli
        attachment.file?.let { filePath ->
            val path = Path.of(filePath)
            if (path.exists()) return path
            logger.debug("Attachment file path from signal-cli does not exist: $filePath")
        }

        // Fall back to ID-based lookup
        val attachmentId = attachment.id ?: return null
        val attachmentsDir = Path.of(config.configDir, "data", config.phoneNumber, "attachments")
        if (!attachmentsDir.exists()) {
            logger.debug("Attachments directory not found: $attachmentsDir")
            return null
        }

        // signal-cli stores attachments as <id> or <id>.<ext>
        val file = attachmentsDir.listDirectoryEntries().firstOrNull { entry ->
            entry.fileName.toString().startsWith(attachmentId)
        }

        if (file == null) {
            logger.debug("Attachment file not found for id: $attachmentId in $attachmentsDir")
        }

        return file
    }
}
