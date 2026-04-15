package fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import fraggle.agent.tool.AgentToolDef
import fraggle.api.FraggleServices
import fraggle.models.ErrorResponse
import fraggle.models.ParameterInfo
import fraggle.models.ToolDetail
import fraggle.models.ToolInfo

/**
 * Tool registry routes — reads from the FraggleToolRegistry.
 */
fun Route.toolRoutes(services: FraggleServices) {
    route("/tools") {
        /**
         * GET /api/v1/tools
         * List all available tools.
         */
        get {
            val tools = services.toolRegistry.tools.map { it.toToolInfo() }
            call.respond(tools)
        }

        /**
         * GET /api/v1/tools/{name}
         * Get detailed information about a specific tool.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tool name"))

            val tool = services.toolRegistry.findTool(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Tool not found"))

            call.respond(tool.toDetail())
        }
    }
}

private fun AgentToolDef<*>.toToolInfo(): ToolInfo = ToolInfo(
    name = name,
    description = description,
    parameters = extractParameterInfos(argsDescriptor),
)

private fun AgentToolDef<*>.toDetail(): ToolDetail = ToolDetail(
    name = name,
    description = description,
    parameters = extractParameterInfos(argsDescriptor),
)

/**
 * Walk a top-level args descriptor and produce [ParameterInfo] entries per property.
 * Mirrors the shape used by [fraggle.agent.tool.FraggleToolRegistry.descriptorToJsonSchema]
 * but flattened to a simple list for REST consumption.
 */
private fun extractParameterInfos(descriptor: SerialDescriptor): List<ParameterInfo> {
    if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) {
        return emptyList()
    }
    return (0 until descriptor.elementsCount).map { i ->
        val elemDescriptor = descriptor.getElementDescriptor(i)
        ParameterInfo(
            name = descriptor.getElementName(i),
            type = elemDescriptor.kind.toTypeName(),
            description = findLlmDescription(descriptor, i),
            required = !descriptor.isElementOptional(i),
        )
    }
}

private fun findLlmDescription(descriptor: SerialDescriptor, index: Int): String {
    for (annotation in descriptor.getElementAnnotations(index)) {
        if (annotation.annotationClass.simpleName == "LLMDescription") {
            return try {
                val method = annotation.annotationClass.java.getMethod("value")
                (method.invoke(annotation) as? String) ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }
    return ""
}

private fun SerialKind.toTypeName(): String = when (this) {
    PrimitiveKind.STRING -> "string"
    PrimitiveKind.BOOLEAN -> "boolean"
    PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
    StructureKind.LIST -> "array"
    StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> "object"
    SerialKind.ENUM -> "enum"
    else -> "any"
}
