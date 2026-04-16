package fraggle.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import fraggle.SkillInstaller
import fraggle.SkillSourceResolver
import fraggle.SkillSourceSpec
import fraggle.SkillsManifest
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillSource
import fraggle.backend.routes.RoutingController
import fraggle.di.AppScope
import fraggle.di.DefaultHttpClient
import fraggle.globalSkillsDir
import fraggle.models.ErrorResponse
import fraggle.models.SkillInstallRequest
import fraggle.models.SkillInstallResponse
import fraggle.models.SkillInstalledEntry
import fraggle.models.SkillPreviewEntry
import fraggle.models.SkillPreviewRequest
import fraggle.models.SkillPreviewResponse
import fraggle.models.SkillsConfig
import fraggle.projectSkillsDir
import java.nio.file.Path
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * API routes for previewing and installing skills from external sources
 * (local paths, GitHub URLs/shorthand, generic git URLs).
 *
 * Lives in fraggle-cli because [SkillSourceResolver] and [SkillInstaller]
 * are CLI-layer utilities. The [ContributesIntoSet] annotation registers
 * this controller alongside the ones from fraggle-api.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SkillInstallRoutes(
    private val skillsConfig: SkillsConfig,
    @param:DefaultHttpClient private val httpClient: HttpClient,
) : RoutingController {
    override fun init(parent: Route) {
        parent.apply {
            route("/skills") {
                post("/preview") {
                    val request = call.receive<SkillPreviewRequest>()
                    val source = request.source.trim()
                    if (source.isEmpty()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Source must not be empty"),
                        )
                    }

                    val targetDir = resolveTarget(request.scope)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid scope: ${request.scope}. Use 'global' or 'project'."),
                        )

                    val spec = SkillSourceSpec.parse(source)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Could not parse source: $source"),
                        )

                    val resolver = SkillSourceResolver(httpClient)
                    val staged = try {
                        resolver.resolve(spec)
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("Failed to resolve source: ${e.message}"),
                        )
                    }

                    try {
                        val loader = SkillLoader()
                        val loadResult = when {
                            staged.path.toFile().isFile &&
                                staged.path.fileName.toString() == SkillLoader.SKILL_FILE_NAME ->
                                loader.loadFromFile(staged.path, SkillSource.EXPLICIT)
                            staged.path.toFile().isDirectory ->
                                loader.loadFromDirectory(staged.path, SkillSource.EXPLICIT)
                            else -> {
                                return@post call.respond(
                                    HttpStatusCode.UnprocessableEntity,
                                    ErrorResponse("Source is neither a SKILL.md nor a directory"),
                                )
                            }
                        }

                        val entries = loadResult.skills.map { skill ->
                            SkillPreviewEntry(
                                name = skill.name,
                                description = skill.description,
                                license = skill.frontmatter.license,
                                compatibility = skill.frontmatter.compatibility,
                                allowedTools = skill.frontmatter.allowedTools,
                                hasPythonDeps = skill.hasPythonDeps,
                                requiredEnv = skill.requiredEnv,
                            )
                        }

                        val diagnosticMessages = loadResult.diagnostics.map { it.toString() }

                        // Look up any prior ignore list for this resolved source
                        // label so the UI can restore the user's choices.
                        val manifest = SkillsManifest.read(SkillInstaller.manifestPath(targetDir))
                        val previouslyIgnored = manifest.ignoredFor(staged.label).toList().sorted()

                        call.respond(
                            SkillPreviewResponse(
                                sourceLabel = staged.label,
                                skills = entries,
                                diagnostics = diagnosticMessages,
                                previouslyIgnored = previouslyIgnored,
                            ),
                        )
                    } finally {
                        staged.cleanup()
                    }
                }

                post("/install") {
                    val request = call.receive<SkillInstallRequest>()
                    val source = request.source.trim()
                    if (source.isEmpty()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Source must not be empty"),
                        )
                    }

                    val targetDir = resolveTarget(request.scope)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid scope: ${request.scope}. Use 'global' or 'project'."),
                        )

                    val spec = SkillSourceSpec.parse(source)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Could not parse source: $source"),
                        )

                    val resolver = SkillSourceResolver(httpClient)
                    val staged = try {
                        resolver.resolve(spec)
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("Failed to resolve source: ${e.message}"),
                        )
                    }

                    try {
                        val installer = SkillInstaller(targetDir = targetDir, force = false)
                        val result = installer.install(
                            source = staged.path,
                            sourceLabel = staged.label,
                            ignored = request.ignored.toSet(),
                        )

                        call.respond(
                            SkillInstallResponse(
                                installed = result.installed.map { inst ->
                                    SkillInstalledEntry(
                                        name = inst.name,
                                        destination = inst.destination.toString(),
                                    )
                                },
                                skipped = result.skipped.map { "${it.name}: ${it.reason}" },
                                diagnostics = result.diagnostics.map { it.toString() },
                            ),
                        )
                    } finally {
                        staged.cleanup()
                    }
                }
            }
        }
    }

    private fun resolveTarget(scope: String): Path? = when (scope) {
        "global" -> globalSkillsDir(skillsConfig)
        "project" -> projectSkillsDir(skillsConfig)
        else -> null
    }
}
