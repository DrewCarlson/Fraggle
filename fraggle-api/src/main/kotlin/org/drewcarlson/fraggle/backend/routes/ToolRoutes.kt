package org.drewcarlson.fraggle.backend.routes

import ai.koog.agents.core.tools.ToolParameterType
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.ParameterInfo
import org.drewcarlson.fraggle.models.ToolDetail
import org.drewcarlson.fraggle.models.ToolInfo

/**
 * Tool registry routes — reads from Koog ToolRegistry.
 */
fun Route.toolRoutes(services: FraggleServices) {
    route("/tools") {
        /**
         * GET /api/v1/tools
         * List all available tools.
         */
        get {
            val tools = services.toolRegistry.tools.map { tool ->
                val desc = tool.descriptor
                ToolInfo(
                    name = desc.name,
                    description = desc.description,
                    parameters = desc.requiredParameters.map { it.toParameterInfo(required = true) } +
                        desc.optionalParameters.map { it.toParameterInfo(required = false) },
                )
            }
            call.respond(tools)
        }

        /**
         * GET /api/v1/tools/{name}
         * Get detailed information about a specific tool.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing tool name"))

            val tool = services.toolRegistry.getToolOrNull(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Tool not found"))

            val desc = tool.descriptor
            val detail = ToolDetail(
                name = desc.name,
                description = desc.description,
                parameters = desc.requiredParameters.map { it.toParameterInfo(required = true) } +
                    desc.optionalParameters.map { it.toParameterInfo(required = false) },
            )
            call.respond(detail)
        }
    }
}

private fun ai.koog.agents.core.tools.ToolParameterDescriptor.toParameterInfo(required: Boolean): ParameterInfo =
    ParameterInfo(
        name = name,
        type = type.toTypeName(),
        description = description,
        required = required,
    )

private fun ToolParameterType.toTypeName(): String = when (this) {
    is ToolParameterType.String -> "string"
    is ToolParameterType.Integer -> "integer"
    is ToolParameterType.Float -> "number"
    is ToolParameterType.Boolean -> "boolean"
    is ToolParameterType.Enum -> "enum"
    is ToolParameterType.List -> "array"
    is ToolParameterType.Null -> "null"
    is ToolParameterType.Object -> "object"
    is ToolParameterType.AnyOf -> "any"
}
