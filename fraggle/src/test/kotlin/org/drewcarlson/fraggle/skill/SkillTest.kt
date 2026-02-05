package org.drewcarlson.fraggle.skill

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillTest {

    @Nested
    inner class Execution {

        @Test
        fun `executes successfully with valid parameters`() = runTest {
            val skill = skill("test_skill") {
                description = "A test skill"
                parameter<String>("input") {
                    description = "Input value"
                    required = true
                }
                execute { params ->
                    val input = params.get<String>("input")
                    SkillResult.Success("Received: $input")
                }
            }

            val params = SkillParameters(mapOf("input" to "hello"))
            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Received: hello", result.output)
        }

        @Test
        fun `returns error when required parameter is missing`() = runTest {
            val skill = skill("test_skill") {
                description = "A test skill"
                parameter<String>("required_param") {
                    description = "Required parameter"
                    required = true
                }
                execute { SkillResult.Success("OK") }
            }

            val params = SkillParameters(emptyMap())
            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("required_param"))
        }

        @Test
        fun `returns error when required parameter is null`() = runTest {
            val skill = skill("test_skill") {
                description = "A test skill"
                parameter<String>("required_param") {
                    description = "Required parameter"
                    required = true
                }
                execute { SkillResult.Success("OK") }
            }

            val params = SkillParameters(mapOf("required_param" to null))
            val result = skill.execute(params)

            assertIs<SkillResult.Error>(result)
        }

        @Test
        fun `executes successfully when optional parameter is missing`() = runTest {
            val skill = skill("test_skill") {
                description = "A test skill"
                parameter<String>("optional_param") {
                    description = "Optional parameter"
                    required = false
                }
                execute { params ->
                    val value = params.getOrDefault("optional_param", "default")
                    SkillResult.Success("Value: $value")
                }
            }

            val params = SkillParameters(emptyMap())
            val result = skill.execute(params)

            assertIs<SkillResult.Success>(result)
            assertEquals("Value: default", result.output)
        }

        @Test
        fun `catches and wraps executor exceptions`() = runTest {
            val skill = skill("throwing_skill") {
                description = "A skill that throws"
                execute { throw RuntimeException("Something went wrong") }
            }

            val result = skill.execute(SkillParameters(emptyMap()))

            assertIs<SkillResult.Error>(result)
            assertTrue(result.message.contains("Something went wrong"))
        }

        @Test
        fun `provides context to executor`() = runTest {
            var capturedContext: SkillContext? = null
            val skill = skill("context_skill") {
                description = "A skill that uses context"
                execute { params ->
                    capturedContext = params.context
                    SkillResult.Success("OK")
                }
            }

            val context = SkillContext(chatId = "chat123", userId = "user456")
            val params = SkillParameters(emptyMap(), context)
            skill.execute(params)

            assertEquals("chat123", capturedContext?.chatId)
            assertEquals("user456", capturedContext?.userId)
        }
    }

    @Nested
    inner class OpenAIFunctionConversion {

        @Test
        fun `converts skill to OpenAI function format`() {
            val skill = skill("my_function") {
                description = "Does something useful"
                parameter<String>("name") {
                    description = "The name"
                    required = true
                }
                parameter<Int>("count") {
                    description = "The count"
                    required = false
                }
                execute { SkillResult.Success("OK") }
            }

            val function = skill.toOpenAIFunction()

            assertEquals("my_function", function.name)
            assertEquals("Does something useful", function.description)
            assertEquals("object", function.parameters.type)
            assertEquals(listOf("name"), function.parameters.required)
            assertTrue(function.parameters.properties.containsKey("name"))
            assertTrue(function.parameters.properties.containsKey("count"))
        }

        @Test
        fun `generates correct JSON schema for string parameter`() {
            val skill = skill("test") {
                description = "Test"
                parameter<String>("text") {
                    description = "A text value"
                    required = true
                }
                execute { SkillResult.Success("OK") }
            }

            val function = skill.toOpenAIFunction()
            val textSchema = function.parameters.properties["text"]!!.jsonObject

            assertEquals("string", textSchema["type"]?.jsonPrimitive?.content)
            assertEquals("A text value", textSchema["description"]?.jsonPrimitive?.content)
        }

        @Test
        fun `generates correct JSON schema for integer parameter`() {
            val skill = skill("test") {
                description = "Test"
                parameter<Int>("number") {
                    description = "A number"
                }
                execute { SkillResult.Success("OK") }
            }

            val function = skill.toOpenAIFunction()
            val schema = function.parameters.properties["number"]!!.jsonObject

            assertEquals("integer", schema["type"]?.jsonPrimitive?.content)
        }

        @Test
        fun `generates correct JSON schema for boolean parameter`() {
            val skill = skill("test") {
                description = "Test"
                parameter<Boolean>("flag") {
                    description = "A flag"
                }
                execute { SkillResult.Success("OK") }
            }

            val function = skill.toOpenAIFunction()
            val schema = function.parameters.properties["flag"]!!.jsonObject

            assertEquals("boolean", schema["type"]?.jsonPrimitive?.content)
        }

        @Test
        fun `generates correct JSON schema for double parameter`() {
            val skill = skill("test") {
                description = "Test"
                parameter<Double>("decimal") {
                    description = "A decimal"
                }
                execute { SkillResult.Success("OK") }
            }

            val function = skill.toOpenAIFunction()
            val schema = function.parameters.properties["decimal"]!!.jsonObject

            assertEquals("number", schema["type"]?.jsonPrimitive?.content)
        }

        @Test
        fun `generates correct JSON schema for enum parameter`() {
            val skill = Skill(
                name = "test",
                description = "Test",
                parameters = listOf(
                    enumParameter(
                        name = "color",
                        description = "A color choice",
                        values = listOf("red", "green", "blue"),
                        required = true,
                    )
                ),
                executor = { SkillResult.Success("OK") },
            )

            val function = skill.toOpenAIFunction()
            val schema = function.parameters.properties["color"]!!.jsonObject

            assertEquals("string", schema["type"]?.jsonPrimitive?.content)
            assertTrue(schema.containsKey("enum"))
        }

        @Test
        fun `generates correct JSON schema for string array parameter`() {
            val skill = Skill(
                name = "test",
                description = "Test",
                parameters = listOf(
                    stringArrayParameter(
                        name = "tags",
                        description = "A list of tags",
                        required = false,
                    )
                ),
                executor = { SkillResult.Success("OK") },
            )

            val function = skill.toOpenAIFunction()
            val schema = function.parameters.properties["tags"]!!.jsonObject

            assertEquals("array", schema["type"]?.jsonPrimitive?.content)
            assertTrue(schema.containsKey("items"))
        }
    }

    @Nested
    inner class SkillResultTests {

        @Test
        fun `Success toResponseString returns output`() {
            val result = SkillResult.Success("Hello world")
            assertEquals("Hello world", result.toResponseString())
        }

        @Test
        fun `Error toResponseString includes error prefix`() {
            val result = SkillResult.Error("Something failed")
            assertEquals("Error: Something failed", result.toResponseString())
        }

        @Test
        fun `ImageAttachment toResponseString returns output`() {
            val result = SkillResult.ImageAttachment(
                imageData = byteArrayOf(1, 2, 3),
                mimeType = "image/png",
                output = "Image generated",
            )
            assertEquals("Image generated", result.toResponseString())
        }

        @Test
        fun `FileAttachment toResponseString returns output`() {
            val result = SkillResult.FileAttachment(
                fileData = byteArrayOf(1, 2, 3),
                filename = "test.txt",
                output = "File created",
            )
            assertEquals("File created", result.toResponseString())
        }

        @Test
        fun `hasAttachment returns true for ImageAttachment`() {
            val result = SkillResult.ImageAttachment(
                imageData = byteArrayOf(),
                mimeType = "image/png",
            )
            assertTrue(result.hasAttachment())
        }

        @Test
        fun `hasAttachment returns true for FileAttachment`() {
            val result = SkillResult.FileAttachment(
                fileData = byteArrayOf(),
                filename = "test.txt",
            )
            assertTrue(result.hasAttachment())
        }

        @Test
        fun `hasAttachment returns false for Success`() {
            val result = SkillResult.Success("OK")
            assertFalse(result.hasAttachment())
        }

        @Test
        fun `hasAttachment returns false for Error`() {
            val result = SkillResult.Error("Failed")
            assertFalse(result.hasAttachment())
        }
    }

    @Nested
    inner class SkillParametersTests {

        @Test
        fun `has returns true for existing key with value`() {
            val params = SkillParameters(mapOf("key" to "value"))
            assertTrue(params.has("key"))
        }

        @Test
        fun `has returns false for missing key`() {
            val params = SkillParameters(emptyMap())
            assertFalse(params.has("key"))
        }

        @Test
        fun `has returns false for null value`() {
            val params = SkillParameters(mapOf("key" to null))
            assertFalse(params.has("key"))
        }

        @Test
        fun `get returns value for existing key`() {
            val params = SkillParameters(mapOf("name" to "Alice"))
            assertEquals("Alice", params.get<String>("name"))
        }

        @Test
        fun `getOrNull returns value for existing key`() {
            val params = SkillParameters(mapOf("name" to "Bob"))
            assertEquals("Bob", params.getOrNull<String>("name"))
        }

        @Test
        fun `getOrNull returns null for missing key`() {
            val params = SkillParameters(emptyMap())
            assertEquals(null, params.getOrNull<String>("name"))
        }

        @Test
        fun `getOrDefault returns value for existing key`() {
            val params = SkillParameters(mapOf("count" to 42))
            assertEquals(42, params.getOrDefault("count", 0))
        }

        @Test
        fun `getOrDefault returns default for missing key`() {
            val params = SkillParameters(emptyMap())
            assertEquals(100, params.getOrDefault("count", 100))
        }

        @Test
        fun `context is accessible`() {
            val context = SkillContext(chatId = "chat1", userId = "user1")
            val params = SkillParameters(emptyMap(), context)
            assertEquals("chat1", params.context?.chatId)
            assertEquals("user1", params.context?.userId)
        }
    }
}
