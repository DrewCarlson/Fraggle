package fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.runBlocking
import fraggle.chat.BridgeInitializer
import fraggle.chat.BridgeInitializerRegistry
import fraggle.chat.CommandResult
import fraggle.chat.InitStepResult
import fraggle.di.AppGraph
import fraggle.events.EventBus
import fraggle.executor.supervision.CliToolPermissionHandler
import fraggle.executor.supervision.EventToolPermissionHandler
import fraggle.models.FraggleConfig
import fraggle.signal.SignalBridgeInitializer
import fraggle.signal.SignalConfig
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.time.Clock

/**
 * Main entry point for Fraggle.
 */
fun main(args: Array<String>) {
    // Resolve FRAGGLE_ROOT before any logger is created so logback's
    // file appender picks up the correct logs directory.
    System.setProperty("FRAGGLE_ROOT", FraggleEnvironment.root.toString())
    Fraggle()
        .subcommands(
            RunCommand(),
            ChatCommand(),
            CodeCommand(),
            ConfigureCommand(),
            InitBridgeCommand(),
            SkillsCommand(),
            WorkerCommand(),
        )
        .main(args)
}

class Fraggle : CliktCommand(name = "fraggle") {
    override fun run() = Unit
}

/**
 * Run the full Fraggle service with Signal integration.
 */
class RunCommand : CliktCommand(name = "run") {
    private val logger = LoggerFactory.getLogger(RunCommand::class.java)

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    override fun run() = runBlocking {
        println("FRAGGLE_ROOT: ${FraggleEnvironment.root}")
        logger.info("Starting Fraggle...")

        // Load configuration
        val (config, resolvedConfigPath) = loadConfig()

        // Create EventBus and permission handler
        val eventBus = EventBus()
        val permissionHandler = EventToolPermissionHandler(eventBus)

        // Create the dependency graph
        val graph =
            createGraphFactory<AppGraph.Factory>().create(config, resolvedConfigPath, eventBus, permissionHandler)

        // Create and initialize orchestrator with injected dependencies
        val orchestrator = graph.serviceOrchestrator
        orchestrator.initialize()

        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutdown signal received...")
            runBlocking {
                orchestrator.stop()
            }
        })

        // Start services
        orchestrator.start()

        // Keep running until interrupted
        logger.info("Fraggle is running. Press Ctrl+C to stop.")

        // Block forever (until shutdown hook)
        while (true) {
            kotlinx.coroutines.delay(1000)
        }
    }

    private fun loadConfig(): Pair<FraggleConfig, Path> {
        val path = if (configPath != null) {
            Path(configPath!!).toAbsolutePath()
        } else {
            FraggleEnvironment.defaultConfigPath
        }

        val existed = path.exists()
        val config = ConfigLoader.loadOrCreateDefault(path)

        if (existed) {
            logger.info("Loaded configuration from: $path")
        } else {
            logger.info("Created default configuration at: $path")
        }

        return config to path
    }
}

/**
 * Interactive chat mode for testing without Signal.
 */
class ChatCommand : CliktCommand(name = "chat") {
    private val logger = LoggerFactory.getLogger(ChatCommand::class.java)

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    private val model by option(
        "-m", "--model",
        help = "Override the model to use"
    )

    override fun run() = runBlocking {
        println("Fraggle Interactive Chat")
        println("========================")
        println("FRAGGLE_ROOT: ${FraggleEnvironment.root}")
        println("Type 'quit' or 'exit' to stop.")
        println()

        // Load configuration
        val (config, resolvedConfigPath) = loadConfig()

        // Show loaded config
        println("Provider: ${config.fraggle.provider.type}")
        println("URL: ${config.fraggle.provider.url}")
        println("Model: ${config.fraggle.provider.model.ifBlank { "(default)" }}")
        println()

        // Create EventBus and CLI permission handler
        val eventBus = EventBus()
        val permissionHandler = CliToolPermissionHandler()

        // Create the dependency graph
        val graph =
            createGraphFactory<AppGraph.Factory>().create(config, resolvedConfigPath, eventBus, permissionHandler)

        // Create and initialize orchestrator with injected dependencies
        val orchestrator = graph.serviceOrchestrator
        orchestrator.initialize()

        val chatCommandProcessor = graph.chatCommandProcessor

        println("Available tools: ${orchestrator.getToolRegistry().tools.map { it.name }}")
        println()

        // Interactive loop
        val chatId = "interactive-${Clock.System.now().toEpochMilliseconds()}"
        val senderId = "local-user"

        while (true) {
            print("You: ")
            val input = readlnOrNull() ?: break

            if (input.lowercase() in listOf("quit", "exit", "q")) {
                println("Goodbye!")
                break
            }

            if (input.isBlank()) continue

            // Dispatch slash commands before they reach the LLM.
            var effectiveInput = input
            if (chatCommandProcessor.isCommand(input)) {
                when (val result = chatCommandProcessor.handleCommand(chatId, input)) {
                    is CommandResult.Expanded -> {
                        effectiveInput = result.newText
                        println("(expanded /skill:${result.skillName})")
                        println()
                    }
                    is CommandResult.Approved -> {
                        println("Tool execution approved.")
                        println()
                        continue
                    }
                    is CommandResult.Denied -> {
                        println("Tool execution denied.")
                        println()
                        continue
                    }
                    is CommandResult.NoPermissionPending -> {
                        println("No pending permission request.")
                        println()
                        continue
                    }
                    is CommandResult.Unknown -> {
                        println("Unknown command: ${result.command}")
                        println()
                        continue
                    }
                    is CommandResult.UnknownSkill -> {
                        println("Unknown skill: ${result.name}")
                        println()
                        continue
                    }
                    is CommandResult.MalformedSkill -> {
                        println("Malformed skill command: ${result.reason}")
                        println()
                        continue
                    }
                    is CommandResult.SkillReadError -> {
                        println("Failed to load skill ${result.name}: ${result.reason}")
                        println()
                        continue
                    }
                }
            }

            try {
                val response = orchestrator.processMessage(
                    chatId = chatId,
                    senderId = senderId,
                    senderName = "User",
                    text = effectiveInput,
                )
                println()
                println("Fraggle: $response")
                println()
            } catch (e: Exception) {
                logger.error("Error processing message: ${e.message}", e)
                println("Error: ${e.message}")
                println()
            }
        }

        orchestrator.stop()
    }

    private fun loadConfig(): Pair<FraggleConfig, Path> {
        val path = if (configPath != null) {
            Path(configPath!!).toAbsolutePath()
        } else {
            FraggleEnvironment.defaultConfigPath
        }

        println("Config path: $path")

        val existed = path.exists()
        val config = ConfigLoader.loadOrCreateDefault(path)

        if (existed) {
            println("Loaded configuration from file...")
        } else {
            println("Created default configuration at: $path")
        }

        // Apply command-line overrides
        val finalConfig = if (model != null) {
            config.copy(
                fraggle = config.fraggle.copy(
                    provider = config.fraggle.provider.copy(model = model!!)
                )
            )
        } else {
            config
        }

        return finalConfig to path
    }
}

/**
 * Initialize a chat bridge interactively.
 *
 * Some bridges require interactive setup (phone verification, OAuth, etc.)
 * that cannot be done through configuration alone.
 */
class InitBridgeCommand : CliktCommand(name = "init-bridge") {
    private val logger = LoggerFactory.getLogger(InitBridgeCommand::class.java)

    private val configPath by option(
        "-c", "--config",
        help = $$"Path to configuration file (default: $FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    private val bridgeName by argument(
        name = "bridge",
        help = "Name of the bridge to initialize (e.g., 'signal')"
    ).optional()

    override fun run() = runBlocking {
        println("Fraggle Bridge Initialization")
        println("==============================")
        println()

        // Load configuration
        val config = loadConfig() ?: return@runBlocking

        // Build registry of available initializers
        val registry = buildInitializerRegistry(config)

        if (registry.names().isEmpty()) {
            println("No bridges are configured for initialization.")
            println("Please configure a bridge in your configuration file first.")
            return@runBlocking
        }

        // If a specific bridge is requested, initialize just that one
        if (bridgeName != null) {
            val initializer = registry.get(bridgeName!!)
            if (initializer == null) {
                println("Unknown bridge: $bridgeName")
                println("Available bridges: ${registry.names().joinToString(", ")}")
                return@runBlocking
            }
            runInitialization(initializer)
            return@runBlocking
        }

        // No bridge specified - find all that need initialization
        val needsInit = registry.all().filter { !it.isInitialized() }

        if (needsInit.isEmpty()) {
            println("All configured bridges are already initialized.")
            println()
            println("Configured bridges:")
            registry.all().forEach { bridge ->
                println("  - ${bridge.bridgeName}: initialized")
            }
            return@runBlocking
        }

        println("The following bridges need initialization:")
        println()
        needsInit.forEach { bridge ->
            println("  - ${bridge.bridgeName}: ${bridge.description}")
        }
        println()

        // Initialize each bridge that needs it
        for (initializer in needsInit) {
            println("─".repeat(50))
            val success = runInitialization(initializer)
            if (!success) {
                print("\nContinue with remaining bridges? (y/n): ")
                val cont = readlnOrNull()?.trim()?.lowercase()
                if (cont != "y" && cont != "yes") {
                    println("Initialization stopped.")
                    return@runBlocking
                }
            }
            println()
        }

        println("─".repeat(50))
        println("Bridge initialization complete.")
    }

    private fun loadConfig(): FraggleConfig? {
        val path = if (configPath != null) {
            Path(configPath!!).toAbsolutePath()
        } else {
            FraggleEnvironment.defaultConfigPath
        }

        return if (path.exists()) {
            println("Config: $path")
            println()
            ConfigLoader.load(path)
        } else {
            println("Configuration file not found: $path")
            println("Please create a configuration file first or specify one with --config")
            null
        }
    }

    private fun buildInitializerRegistry(config: FraggleConfig): BridgeInitializerRegistry {
        val registry = BridgeInitializerRegistry()

        // Register Signal initializer if configured
        config.fraggle.bridges.signal?.let { signalConfig ->
            if (signalConfig.enabled && signalConfig.phone.isNotBlank()) {
                val signalInitConfig = SignalConfig(
                    phoneNumber = signalConfig.phone,
                    configDir = FraggleEnvironment.resolvePath(signalConfig.configDir).toString(),
                    triggerPrefix = signalConfig.trigger,
                    signalCliPath = signalConfig.signalCliPath,
                    autoInstall = signalConfig.autoInstall,
                    signalCliVersion = signalConfig.signalCliVersion,
                    appsDir = FraggleEnvironment.dataDir.resolve("apps").toString(),
                    profileName = signalConfig.profileName,
                )
                registry.register("signal", SignalBridgeInitializer(signalInitConfig))
            }
        }

        // Future bridges would be registered here:
        // config.fraggle.bridges.discord?.let { ... }
        // config.fraggle.bridges.telegram?.let { ... }

        return registry
    }

    private suspend fun runInitialization(initializer: BridgeInitializer): Boolean {
        println("Initializing ${initializer.bridgeName}...")
        println(initializer.description)
        println()

        var userInput: String? = null

        while (true) {
            val result = initializer.initialize(userInput)
            userInput = null

            when (result) {
                is InitStepResult.Success -> {
                    result.message?.let { println(it) }
                    // Continue to next step
                }

                is InitStepResult.PromptRequired -> {
                    result.helpText?.let {
                        println()
                        println(it)
                        println()
                    }

                    print("${result.prompt}: ")
                    System.out.flush()

                    userInput = if (result.sensitive) {
                        // For sensitive input, try to use console for hidden input
                        val console = System.console()
                        if (console != null) {
                            String(console.readPassword())
                        } else {
                            readlnOrNull()
                        }
                    } else {
                        readlnOrNull()
                    }

                    if (userInput == null) {
                        println("\nInitialization cancelled.")
                        return false
                    }

                    if (userInput.lowercase() == "q" || userInput.lowercase() == "quit") {
                        println("\nInitialization cancelled.")
                        return false
                    }
                }

                is InitStepResult.Error -> {
                    println()
                    println("Error: ${result.message}")
                    println()

                    if (!result.recoverable) {
                        println("This error cannot be recovered from. Please fix the issue and try again.")
                        return false
                    }

                    print("Try again? (y/n): ")
                    val retry = readlnOrNull()?.trim()?.lowercase()
                    if (retry != "y" && retry != "yes") {
                        println("Initialization cancelled.")
                        return false
                    }

                    initializer.reset()
                }

                is InitStepResult.Complete -> {
                    println()
                    println("Success: ${result.message}")
                    println()
                    return true
                }
            }
        }
    }
}
