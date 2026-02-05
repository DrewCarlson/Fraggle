package org.drewcarlson.fraggle.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a skill that the agent can execute.
 * Skills are type-safe, validated, and provide structured tool calling for LLMs.
 */
data class Skill(
    val name: String,
    val description: String,
    val parameters: List<SkillParameter<*>>,
    private val executor: suspend (SkillParameters) -> SkillResult,
) {
    /**
     * Execute this skill with the given parameters.
     */
    suspend fun execute(params: SkillParameters): SkillResult {
        return try {
            // Validate all required parameters
            for (param in parameters) {
                if (param.required && !params.has(param.name)) {
                    return SkillResult.Error("Missing required parameter: ${param.name}")
                }
            }
            executor(params)
        } catch (e: Exception) {
            SkillResult.Error("Skill execution failed: ${e.message}")
        }
    }

    /**
     * Convert this skill to OpenAI function calling format.
     */
    fun toOpenAIFunction(): OpenAIFunction {
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<String>()

        for (param in parameters) {
            properties[param.name] = param.toJsonSchema()
            if (param.required) {
                required.add(param.name)
            }
        }

        return OpenAIFunction(
            name = name,
            description = description,
            parameters = OpenAIFunctionParameters(
                type = "object",
                properties = JsonObject(properties),
                required = required,
            ),
        )
    }
}

/**
 * Represents a parameter definition for a skill.
 */
data class SkillParameter<T : Any>(
    val name: String,
    val description: String,
    val type: ParameterType,
    val required: Boolean,
    val default: T?,
    val validator: ((T) -> Boolean)?,
) {
    fun toJsonSchema(): JsonElement {
        return type.toJsonSchema(description)
    }
}

/**
 * Supported parameter types for skills.
 */
sealed class ParameterType {
    data object StringType : ParameterType()
    data object IntType : ParameterType()
    data object LongType : ParameterType()
    data object DoubleType : ParameterType()
    data object BooleanType : ParameterType()
    data class EnumType(val values: List<String>) : ParameterType()
    data object StringArrayType : ParameterType()

    fun toJsonSchema(description: String): JsonElement {
        return when (this) {
            is StringType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("string"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                )
            )
            is IntType, is LongType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("integer"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                )
            )
            is DoubleType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("number"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                )
            )
            is BooleanType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("boolean"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                )
            )
            is EnumType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("string"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                    "enum" to kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            )
            is StringArrayType -> JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("array"),
                    "description" to kotlinx.serialization.json.JsonPrimitive(description),
                    "items" to JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("string"))),
                )
            )
        }
    }
}

/**
 * Execution context for skills, providing access to chat information.
 */
data class SkillContext(
    /**
     * The ID of the chat where the skill is being executed.
     */
    val chatId: String,

    /**
     * The ID of the user who triggered the skill.
     */
    val userId: String? = null,
)

/**
 * Container for skill execution parameters with type-safe access.
 */
class SkillParameters(
    private val values: Map<String, Any?>,
    /**
     * The execution context, if available.
     */
    val context: SkillContext? = null,
) {
    fun has(name: String): Boolean = values.containsKey(name) && values[name] != null

    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String): T {
        return values[name] as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrNull(name: String): T? {
        return values[name] as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrDefault(name: String, default: T): T {
        return values[name] as? T ?: default
    }
}

/**
 * Result of skill execution.
 */
sealed class SkillResult {
    /**
     * Successful execution with a text result.
     */
    data class Success(val output: String) : SkillResult()

    /**
     * Execution failed with an error message.
     */
    data class Error(val message: String) : SkillResult()

    /**
     * Successful execution with an image attachment to send.
     */
    data class ImageAttachment(
        val imageData: ByteArray,
        val mimeType: String,
        val caption: String? = null,
        val output: String = "Image sent successfully",
    ) : SkillResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageAttachment) return false
            return imageData.contentEquals(other.imageData) &&
                mimeType == other.mimeType &&
                caption == other.caption &&
                output == other.output
        }

        override fun hashCode(): Int {
            var result = imageData.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (caption?.hashCode() ?: 0)
            result = 31 * result + output.hashCode()
            return result
        }
    }

    /**
     * Successful execution with a file attachment to send.
     */
    data class FileAttachment(
        val fileData: ByteArray,
        val filename: String,
        val mimeType: String? = null,
        val output: String = "File sent successfully",
    ) : SkillResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileAttachment) return false
            return fileData.contentEquals(other.fileData) &&
                filename == other.filename &&
                mimeType == other.mimeType &&
                output == other.output
        }

        override fun hashCode(): Int {
            var result = fileData.contentHashCode()
            result = 31 * result + filename.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + output.hashCode()
            return result
        }
    }

    fun toResponseString(): String = when (this) {
        is Success -> output
        is Error -> "Error: $message"
        is ImageAttachment -> output
        is FileAttachment -> output
    }

    /**
     * Check if this result has an attachment to send.
     */
    fun hasAttachment(): Boolean = this is ImageAttachment || this is FileAttachment
}

/**
 * OpenAI function calling format for tool definitions.
 */
@Serializable
data class OpenAIFunction(
    val name: String,
    val description: String,
    val parameters: OpenAIFunctionParameters,
)

@Serializable
data class OpenAIFunctionParameters(
    val type: String,
    val properties: JsonObject,
    val required: List<String>,
)

@Serializable
data class OpenAITool(
    val type: String = "function",
    val function: OpenAIFunction,
)
