package org.drewcarlson.fraggle.agent

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Processes inline image syntax in LLM responses and extracts/downloads images.
 *
 * Supported syntax:
 * - `[[image:https://example.com/photo.jpg]]` - remote image URL
 * - `[[image:/path/to/local/file.jpg]]` - local file path
 *
 * The processor extracts the first image reference, downloads it if remote,
 * and returns the cleaned text along with the image data.
 */
class InlineImageProcessor {
    private val logger = LoggerFactory.getLogger(InlineImageProcessor::class.java)

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "Fraggle/1.0")
        }
    }

    companion object {
        // Matches [[image:url]] where url can be http(s):// or a local path
        private val IMAGE_PATTERN = Regex("""\[\[image:([^]]+)]]""", RegexOption.IGNORE_CASE)

        // Maximum image size (50MB)
        private const val MAX_IMAGE_SIZE = 50 * 1024 * 1024

        // Supported image extensions
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    /**
     * Result of processing text for inline images.
     */
    data class ProcessResult(
        /** The text with inline image syntax removed */
        val cleanedText: String,
        /** The extracted image, if any */
        val image: InlineImage?,
    )

    /**
     * An extracted inline image.
     */
    data class InlineImage(
        val data: ByteArray,
        val mimeType: String,
        val sourceUrl: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InlineImage) return false
            return data.contentEquals(other.data) && mimeType == other.mimeType && sourceUrl == other.sourceUrl
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + sourceUrl.hashCode()
            return result
        }
    }

    /**
     * Process text for inline image syntax.
     * Extracts the first image reference, downloads it if remote, and returns cleaned text.
     *
     * @param text The text to process
     * @return ProcessResult with cleaned text and optional image
     */
    suspend fun process(text: String): ProcessResult {
        val match = IMAGE_PATTERN.find(text) ?: return ProcessResult(text, null)

        val imageUrl = match.groupValues[1].trim()
        logger.debug("Found inline image reference: $imageUrl")

        // Remove the image syntax from the text (only the first one)
        val cleanedText = text.replaceFirst(IMAGE_PATTERN, "").trim()

        // Try to load the image
        val image = try {
            when {
                imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                    downloadRemoteImage(imageUrl)
                }
                imageUrl.startsWith("file://") -> {
                    loadLocalImage(imageUrl.removePrefix("file://"))
                }
                imageUrl.startsWith("/") || imageUrl.matches(Regex("^[a-zA-Z]:.*")) -> {
                    loadLocalImage(imageUrl)
                }
                else -> {
                    logger.warn("Unsupported image URL format: $imageUrl")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load inline image: ${e.message}")
            null
        }

        return ProcessResult(cleanedText, image)
    }

    /**
     * Download an image from a remote URL.
     */
    private suspend fun downloadRemoteImage(url: String): InlineImage? {
        logger.debug("Downloading remote image: $url")

        val response = httpClient.get(url)
        val statusCode = response.status.value

        if (statusCode !in 200..299) {
            logger.warn("Failed to download image: HTTP $statusCode")
            return null
        }

        val contentType = response.contentType()?.toString() ?: "application/octet-stream"

        // Verify it's an image
        val isImage = contentType.startsWith("image/") || isImageUrl(url)
        if (!isImage) {
            logger.warn("URL does not appear to be an image: $url (Content-Type: $contentType)")
            return null
        }

        val imageData = response.readRawBytes()

        if (imageData.size > MAX_IMAGE_SIZE) {
            logger.warn("Image too large: ${imageData.size / 1024}KB (max ${MAX_IMAGE_SIZE / 1024 / 1024}MB)")
            return null
        }

        val mimeType = inferMimeType(contentType, url)
        logger.info("Downloaded inline image: ${imageData.size / 1024}KB ($mimeType)")

        return InlineImage(imageData, mimeType, url)
    }

    /**
     * Load an image from a local file path.
     */
    private fun loadLocalImage(pathString: String): InlineImage? {
        logger.debug("Loading local image: $pathString")

        val path = Path(pathString)
        if (!path.exists()) {
            logger.warn("Local image file does not exist: $path")
            return null
        }

        if (!path.isRegularFile()) {
            logger.warn("Path is not a file: $path")
            return null
        }

        if (!isImageUrl(pathString)) {
            logger.warn("File does not appear to be an image: $path")
            return null
        }

        if (path.fileSize() > MAX_IMAGE_SIZE) {
            logger.warn("Image file too large: ${path.fileSize() / 1024}KB")
            return null
        }

        val imageData = path.readBytes()
        val mimeType = inferMimeType(null, pathString)
        logger.info("Loaded local image: ${imageData.size / 1024}KB ($mimeType)")

        return InlineImage(imageData, mimeType, pathString)
    }

    private fun isImageUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return IMAGE_EXTENSIONS.any { lowerUrl.endsWith(".$it") }
    }

    private fun inferMimeType(contentType: String?, url: String): String {
        // Use content-type if it's a valid image type
        if (contentType?.startsWith("image/") == true) {
            return contentType
        }

        // Infer from URL extension
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.endsWith(".png") -> "image/png"
            lowerUrl.endsWith(".gif") -> "image/gif"
            lowerUrl.endsWith(".webp") -> "image/webp"
            lowerUrl.endsWith(".bmp") -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    /**
     * Check if text contains any inline image syntax.
     */
    fun hasInlineImage(text: String): Boolean {
        return IMAGE_PATTERN.containsMatchIn(text)
    }

    /**
     * Remove all inline image syntax from text without processing.
     */
    fun stripInlineImages(text: String): String {
        return text.replace(IMAGE_PATTERN, "").trim()
    }
}
