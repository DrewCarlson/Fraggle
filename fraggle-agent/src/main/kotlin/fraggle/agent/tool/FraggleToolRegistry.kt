package fraggle.agent.tool

import fraggle.agent.loop.ToolDefinition
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Registry of agent tools with schema generation.
 * Replaces Koog's ToolRegistry for the new agent loop.
 */
class FraggleToolRegistry(
    val tools: List<AgentToolDef<*>>,
) {
    fun findTool(name: String): AgentToolDef<*>? = tools.firstOrNull { it.name == name }

    /** Generate ToolDefinitions for the LLM bridge. */
    fun toToolDefinitions(): List<ToolDefinition> = tools.map { tool ->
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            parametersSchema = descriptorToJsonSchema(tool.argsDescriptor).toString(),
        )
    }

    companion object {
        /**
         * Convert a kotlinx.serialization [SerialDescriptor] to a JSON Schema object.
         */
        fun descriptorToJsonSchema(descriptor: SerialDescriptor): JsonObject {
            return when (descriptor.kind) {
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        val required = mutableListOf<String>()
                        putJsonObject("properties") {
                            for (i in 0 until descriptor.elementsCount) {
                                val elementName = descriptor.getElementName(i)
                                val elementDescriptor = descriptor.getElementDescriptor(i)
                                val isOptional = descriptor.isElementOptional(i)
                                putJsonObject(elementName) {
                                    val elementSchema = elementToSchema(elementDescriptor)
                                    for ((key, value) in elementSchema) {
                                        put(key, value)
                                    }
                                    // Extract @LLMDescription if present
                                    val description = extractDescription(descriptor, i)
                                    if (description != null) {
                                        put("description", JsonPrimitive(description))
                                    }
                                }
                                if (!isOptional) {
                                    required.add(elementName)
                                }
                            }
                        }
                        if (required.isNotEmpty()) {
                            putJsonArray("required") {
                                required.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                }
                else -> elementToSchema(descriptor)
            }
        }

        private fun elementToSchema(descriptor: SerialDescriptor): JsonObject {
            return when (descriptor.kind) {
                PrimitiveKind.STRING -> buildJsonObject { put("type", JsonPrimitive("string")) }
                PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                    buildJsonObject { put("type", JsonPrimitive("integer")) }
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                    buildJsonObject { put("type", JsonPrimitive("number")) }
                PrimitiveKind.BOOLEAN ->
                    buildJsonObject { put("type", JsonPrimitive("boolean")) }
                StructureKind.LIST -> {
                    val elementDescriptor = descriptor.getElementDescriptor(0)
                    buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        putJsonObject("items") {
                            val itemSchema = elementToSchema(elementDescriptor)
                            for ((key, value) in itemSchema) {
                                put(key, value)
                            }
                        }
                    }
                }
                StructureKind.MAP -> {
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        putJsonObject("additionalProperties") {
                            val valueDescriptor = descriptor.getElementDescriptor(1)
                            val valueSchema = elementToSchema(valueDescriptor)
                            for ((key, value) in valueSchema) {
                                put(key, value)
                            }
                        }
                    }
                }
                StructureKind.CLASS, StructureKind.OBJECT -> descriptorToJsonSchema(descriptor)
                SerialKind.ENUM -> {
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        putJsonArray("enum") {
                            for (i in 0 until descriptor.elementsCount) {
                                add(JsonPrimitive(descriptor.getElementName(i)))
                            }
                        }
                    }
                }
                else -> buildJsonObject { put("type", JsonPrimitive("string")) }
            }
        }

        /**
         * Extract description from @LLMDescription annotation if present.
         */
        private fun extractDescription(descriptor: SerialDescriptor, index: Int): String? {
            val annotations = descriptor.getElementAnnotations(index)
            for (annotation in annotations) {
                // Check by class name since LLMDescription may be in a different package
                val className = annotation.annotationClass.simpleName
                if (className == "LLMDescription") {
                    // Use reflection to get the value
                    return try {
                        val valueMethod = annotation.annotationClass.java.getMethod("value")
                        valueMethod.invoke(annotation) as? String
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return null
        }
    }
}
