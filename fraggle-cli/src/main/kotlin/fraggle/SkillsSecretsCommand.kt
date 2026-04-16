package fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillSecretsStore
import fraggle.agent.skill.SkillSource
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

/**
 * `fraggle skills secrets` — manage environment variable secrets for skills.
 *
 * Skills declare required env vars in their SKILL.md frontmatter via the `env`
 * field. This command family lets you set, list, check, and remove those values.
 * Values are stored per-skill under `{FRAGGLE_ROOT}/secrets/`.
 */
class SkillsSecretsCommand : CliktCommand(name = "secrets") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Manage environment variable secrets for skills"

    override fun run() = Unit

    init {
        subcommands(
            SecretsSetCommand(),
            SecretsListCommand(),
            SecretsCheckCommand(),
            SecretsRemoveCommand(),
        )
    }
}

// ---------- secrets set ----------

private class SecretsSetCommand : CliktCommand(name = "set") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Set a secret value for a skill"

    private val skillName by argument(name = "skill", help = "Skill name")
    private val varName by argument(name = "var", help = "Environment variable name")
    private val valueOpt by option(
        "--value",
        help = "Secret value. If omitted, reads from stdin (avoids shell history).",
    )

    override fun run() {
        val store = SkillSecretsStore(FraggleEnvironment.secretsDir)
        val value = valueOpt ?: run {
            print("Enter value for $varName: ")
            System.out.flush()
            readlnOrNull()?.also { println() } ?: run {
                println("No input received.")
                exitProcess(1)
            }
        }
        store.set(skillName, varName, value)
        println("Set $varName for skill '$skillName'.")
    }
}

// ---------- secrets list ----------

private class SecretsListCommand : CliktCommand(name = "list") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List configured secret names for a skill (values are not shown)"

    private val skillName by argument(name = "skill", help = "Skill name")

    override fun run() {
        val store = SkillSecretsStore(FraggleEnvironment.secretsDir)
        val configured = store.listConfigured(skillName)
        if (configured.isEmpty()) {
            println("No secrets configured for '$skillName'.")
            return
        }
        println("Configured secrets for '$skillName':")
        for (name in configured.sorted()) {
            println("  $name")
        }
    }
}

// ---------- secrets check ----------

private class SecretsCheckCommand : CliktCommand(name = "check") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Check which required env vars are configured vs missing for a skill"

    private val skillName by argument(name = "skill", help = "Skill name")
    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)",
    )

    override fun run() {
        val config = loadSkillsConfig(configPath)
        val (skills, _) = loadConfiguredSkills(config, SkillTarget.BOTH)
        val skill = skills.firstOrNull { it.name == skillName }

        if (skill == null) {
            println("Skill '$skillName' not found in configured skill directories.")
            exitProcess(1)
        }

        if (skill.requiredEnv.isEmpty()) {
            println("Skill '$skillName' does not declare any required env vars.")
            return
        }

        val store = SkillSecretsStore(FraggleEnvironment.secretsDir)
        val configured = store.listConfigured(skillName)

        println("Env var status for '$skillName':")
        var hasMissing = false
        for (varName in skill.requiredEnv) {
            val status = if (varName in configured) "configured" else {
                hasMissing = true
                "MISSING"
            }
            println("  %-30s  %s".format(varName, status))
        }

        // Also show any configured vars not in the required list.
        val extra = configured - skill.requiredEnv.toSet()
        if (extra.isNotEmpty()) {
            println()
            println("Extra configured (not in skill's env list):")
            for (varName in extra.sorted()) {
                println("  $varName")
            }
        }

        if (hasMissing) {
            println()
            println("Run: fraggle skills secrets set $skillName <VAR>")
        }
    }
}

// ---------- secrets remove ----------

private class SecretsRemoveCommand : CliktCommand(name = "remove") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Remove a secret for a skill"

    private val skillName by argument(name = "skill", help = "Skill name")
    private val varName by argument(name = "var", help = "Environment variable name to remove")

    override fun run() {
        val store = SkillSecretsStore(FraggleEnvironment.secretsDir)
        if (store.remove(skillName, varName)) {
            println("Removed $varName from skill '$skillName'.")
        } else {
            println("$varName was not configured for skill '$skillName'.")
        }
    }
}
