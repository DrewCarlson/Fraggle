package fraggle.agent

/**
 * Attachment to be sent with the response. Collected by tools during execution
 * via [ToolExecutionContext.attachments].
 */
sealed class ResponseAttachment {
    data class Image(
        val data: ByteArray,
        val mimeType: String,
        val caption: String? = null,
    ) : ResponseAttachment() {
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

    data class File(
        val data: ByteArray,
        val filename: String,
        val mimeType: String? = null,
    ) : ResponseAttachment() {
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
}
