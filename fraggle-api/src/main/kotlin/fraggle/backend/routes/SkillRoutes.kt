package fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.agent.skill.Skill
import fraggle.api.FraggleServices
import fraggle.models.ErrorResponse
import fraggle.models.SkillDetail
import fraggle.models.SkillInfo
import kotlin.io.path.readText

/**
 * Skill registry routes — reads from the SkillRegistry.
 */
fun Route.skillRoutes(services: FraggleServices) {
    route("/skills") {
        /**
         * GET /api/v1/skills
         * List all loaded skills.
         */
        get {
            val skills = services.skillRegistry.skills.map { it.toInfo() }
            call.respond(skills)
        }

        /**
         * GET /api/v1/skills/{name}
         * Get detailed information about a specific skill, including the full body.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

            val skill = services.skillRegistry.findByName(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found"))

            val body = runCatching { skill.filePath.readText() }.getOrElse { "" }
            call.respond(skill.toDetail(body))
        }
    }
}

private fun Skill.toInfo(): SkillInfo = SkillInfo(
    name = name,
    description = description,
    source = source.name,
    disableModelInvocation = disableModelInvocation,
)

private fun Skill.toDetail(body: String): SkillDetail = SkillDetail(
    name = name,
    description = description,
    source = source.name,
    filePath = filePath.toString(),
    baseDir = baseDir.toString(),
    disableModelInvocation = disableModelInvocation,
    license = frontmatter.license,
    compatibility = frontmatter.compatibility,
    allowedTools = frontmatter.allowedTools,
    body = body,
)
