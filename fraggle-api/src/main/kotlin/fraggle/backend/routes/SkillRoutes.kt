package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.agent.skill.Skill
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import fraggle.models.ErrorResponse
import fraggle.models.SkillDetail
import fraggle.models.SkillInfo
import kotlin.io.path.readText

/**
 * Skill registry routes — reads from the SkillRegistry.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SkillRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.apply {
            route("/skills") {
                get {
                    val skills = services.skillRegistry.skills.map { it.toInfo() }
                    call.respond(skills)
                }

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
