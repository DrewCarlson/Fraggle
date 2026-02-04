package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.ParameterInfo
import org.drewcarlson.fraggle.models.SkillDetail
import org.drewcarlson.fraggle.models.SkillInfo
import org.drewcarlson.fraggle.skill.ParameterType

/**
 * Skill registry routes.
 */
fun Route.skillRoutes(services: FraggleServices) {
    route("/skills") {
        /**
         * GET /api/v1/skills
         * List all available skills.
         */
        get {
            val skills = services.skills.all().map { skill ->
                SkillInfo(
                    name = skill.name,
                    description = skill.description,
                    parameters = skill.parameters.map { param ->
                        ParameterInfo(
                            name = param.name,
                            type = param.type.toTypeName(),
                            description = param.description,
                            required = param.required,
                        )
                    },
                )
            }
            call.respond(skills)
        }

        /**
         * GET /api/v1/skills/{name}
         * Get detailed information about a specific skill.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

            val skill = services.skills.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found"))

            val info = SkillDetail(
                name = skill.name,
                description = skill.description,
                parameters = skill.parameters.map { param ->
                    ParameterInfo(
                        name = param.name,
                        type = param.type.toTypeName(),
                        description = param.description,
                        required = param.required,
                    )
                },
            )
            call.respond(info)
        }
    }
}

private fun ParameterType.toTypeName(): String = when (this) {
    is ParameterType.StringType -> "string"
    is ParameterType.IntType -> "integer"
    is ParameterType.LongType -> "integer"
    is ParameterType.DoubleType -> "number"
    is ParameterType.BooleanType -> "boolean"
    is ParameterType.EnumType -> "enum"
    is ParameterType.StringArrayType -> "array"
}
