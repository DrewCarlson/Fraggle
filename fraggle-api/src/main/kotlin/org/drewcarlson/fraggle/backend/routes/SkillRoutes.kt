package org.drewcarlson.fraggle.backend.routes

import ai.koog.agents.core.tools.ToolParameterType
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.ParameterInfo
import org.drewcarlson.fraggle.models.SkillDetail
import org.drewcarlson.fraggle.models.SkillInfo

/**
 * Skill registry routes — reads from Koog ToolRegistry.
 */
fun Route.skillRoutes(services: FraggleServices) {
    route("/skills") {
        /**
         * GET /api/v1/skills
         * List all available tools (exposed as "skills" for API compatibility).
         */
        get {
            val skills = services.toolRegistry.tools.map { tool ->
                val desc = tool.descriptor
                SkillInfo(
                    name = desc.name,
                    description = desc.description,
                    parameters = desc.requiredParameters.map { it.toParameterInfo(required = true) } +
                        desc.optionalParameters.map { it.toParameterInfo(required = false) },
                )
            }
            call.respond(skills)
        }

        /**
         * GET /api/v1/skills/{name}
         * Get detailed information about a specific tool.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

            val tool = services.toolRegistry.getToolOrNull(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found"))

            val desc = tool.descriptor
            val info = SkillDetail(
                name = desc.name,
                description = desc.description,
                parameters = desc.requiredParameters.map { it.toParameterInfo(required = true) } +
                    desc.optionalParameters.map { it.toParameterInfo(required = false) },
            )
            call.respond(info)
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
