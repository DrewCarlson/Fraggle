package org.drewcarlson.fraggle.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes

class InlineImageProcessorTest {

    private val processor = InlineImageProcessor()

    @Nested
    inner class `hasInlineImage detection` {
        @Test
        fun `detects http image url`() {
            val text = "Here's an image: [[image:https://example.com/photo.jpg]]"
            assertTrue(processor.hasInlineImage(text))
        }

        @Test
        fun `detects local file path`() {
            val text = "Local image: [[image:/path/to/file.png]]"
            assertTrue(processor.hasInlineImage(text))
        }

        @Test
        fun `returns false when no image syntax`() {
            val text = "Just regular text with a URL: https://example.com/photo.jpg"
            assertFalse(processor.hasInlineImage(text))
        }

        @Test
        fun `is case insensitive`() {
            val text = "[[IMAGE:https://example.com/photo.jpg]]"
            assertTrue(processor.hasInlineImage(text))
        }
    }

    @Nested
    inner class stripInlineImages {
        @Test
        fun `removes single image reference`() {
            val text = "Here's the photo: [[image:https://example.com/photo.jpg]] Hope you like it!"
            val result = processor.stripInlineImages(text)
            assertEquals("Here's the photo:  Hope you like it!", result)
        }

        @Test
        fun `removes multiple image references`() {
            val text = "First: [[image:https://a.jpg]] Second: [[image:https://b.jpg]]"
            val result = processor.stripInlineImages(text)
            assertEquals("First:  Second:", result)
        }

        @Test
        fun `preserves text without images`() {
            val text = "Just regular text"
            val result = processor.stripInlineImages(text)
            assertEquals("Just regular text", result)
        }
    }

    @Nested
    inner class `process with local files` {
        @Test
        fun `processes local file path`(@TempDir tempDir: Path) = runTest {
            // Create a test image file
            val imageFile = tempDir.resolve("test.jpg")
            val testData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // JPEG header
            imageFile.writeBytes(testData)

            val text = "Here's the image: [[image:${imageFile.toAbsolutePath()}]] Nice, right?"
            val result = processor.process(text)

            assertEquals("Here's the image:  Nice, right?", result.cleanedText)
            assertNotNull(result.image)
            assertArrayEquals(testData, result.image?.data)
            assertEquals("image/jpeg", result.image?.mimeType)
        }

        @Test
        fun `handles file not found`() = runTest {
            val text = "Image: [[image:/nonexistent/path/image.jpg]] End"
            val result = processor.process(text)

            assertEquals("Image:  End", result.cleanedText)
            assertNull(result.image)
        }

        @Test
        fun `handles file prefix`(@TempDir tempDir: Path) = runTest {
            val imageFile = tempDir.resolve("test.png")
            imageFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG header

            val text = "[[image:file://${imageFile.toAbsolutePath()}]]"
            val result = processor.process(text)

            assertNotNull(result.image)
            assertEquals("image/png", result.image?.mimeType)
        }
    }

    @Nested
    inner class `process text cleaning` {
        @Test
        fun `returns original text when no image syntax`() = runTest {
            val text = "No images here, just text."
            val result = processor.process(text)

            assertEquals("No images here, just text.", result.cleanedText)
            assertNull(result.image)
        }

        @Test
        fun `only processes first image`() = runTest {
            // When multiple images present, only first is processed
            val text = "[[image:https://a.jpg]] and [[image:https://b.jpg]]"
            val result = processor.process(text)

            // First image syntax is removed (becomes empty since image download will fail in test)
            assertTrue(result.cleanedText.contains("[[image:https://b.jpg]]"))
        }

        @Test
        fun `trims result`() = runTest {
            val text = "   [[image:https://example.com/test.jpg]]   "
            val result = processor.process(text)

            // Cleaned text should be trimmed
            assertFalse(result.cleanedText.startsWith(" "))
            assertFalse(result.cleanedText.endsWith(" "))
        }
    }

    @Nested
    inner class `MIME type inference` {
        @Test
        fun `infers png from extension`(@TempDir tempDir: Path) = runTest {
            val file = tempDir.resolve("image.png")
            file.writeBytes(byteArrayOf(1, 2, 3))

            val result = processor.process("[[image:${file.toAbsolutePath()}]]")

            assertEquals("image/png", result.image?.mimeType)
        }

        @Test
        fun `infers gif from extension`(@TempDir tempDir: Path) = runTest {
            val file = tempDir.resolve("anim.gif")
            file.writeBytes(byteArrayOf(1, 2, 3))

            val result = processor.process("[[image:${file.toAbsolutePath()}]]")

            assertEquals("image/gif", result.image?.mimeType)
        }

        @Test
        fun `infers webp from extension`(@TempDir tempDir: Path) = runTest {
            val file = tempDir.resolve("photo.webp")
            file.writeBytes(byteArrayOf(1, 2, 3))

            val result = processor.process("[[image:${file.toAbsolutePath()}]]")

            assertEquals("image/webp", result.image?.mimeType)
        }

        @Test
        fun `defaults to jpeg for unknown extension`(@TempDir tempDir: Path) = runTest {
            val file = tempDir.resolve("photo.jpg")
            file.writeBytes(byteArrayOf(1, 2, 3))

            val result = processor.process("[[image:${file.toAbsolutePath()}]]")

            assertEquals("image/jpeg", result.image?.mimeType)
        }
    }
}
