package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.agent.skill.Skill
import fraggle.agent.skill.SkillRegistryLoader
import fraggle.di.AppScope
import fraggle.models.ErrorResponse
import fraggle.models.SkillDetail
import fraggle.models.SkillInfo
import fraggle.models.SkillsConfig
import kotlin.io.path.readText

/**
 * Skill registry routes — rescans disk on every request so newly-installed
 * skills show up in the dashboard without a server restart. This is a
 * deliberate departure from the cached startup snapshot the agent loop uses.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SkillRoutes(
    private val skillsConfig: SkillsConfig,
    private val registryLoader: SkillRegistryLoader,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/skills") {
            get { listSkills() }
            get("/{name}") { getSkill() }
        }
    }

    suspend fun RoutingContext.listSkills() {
        val registry = registryLoader.load(skillsConfig)
        call.respond(registry.skills.map { it.toInfo() })
    }

    suspend fun RoutingContext.getSkill() {
        val name = call.parameters["name"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill name"))

        val registry = registryLoader.load(skillsConfig)
        val skill = registry.findByName(name)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found"))

        val body = runCatching { skill.filePath.readText() }.getOrElse { "" }
        call.respond(skill.toDetail(body))
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
