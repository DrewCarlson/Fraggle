package org.drewcarlson.fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Main entry point for Fraggle.
 */
fun main(args: Array<String>) = Fraggle()
    .subcommands(RunCommand(), ChatCommand(), TestSignalCommand())
    .main(args)

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
        help = "Path to configuration file (default: \$FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    override fun run() = runBlocking {
        println("FRAGGLE_ROOT: ${FraggleEnvironment.root}")
        logger.info("Starting Fraggle...")

        // Load configuration
        val config = loadConfig()

        // Create and initialize orchestrator
        val orchestrator = ServiceOrchestrator(config)
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

    private fun loadConfig(): FraggleConfig {
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

        return config
    }
}

/**
 * Interactive chat mode for testing without Signal.
 */
class ChatCommand : CliktCommand(name = "chat") {
    private val logger = LoggerFactory.getLogger(ChatCommand::class.java)

    private val configPath by option(
        "-c", "--config",
        help = "Path to configuration file (default: \$FRAGGLE_ROOT/config/fraggle.yaml)"
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
        val config = loadConfig()

        // Show loaded config
        println("Provider: ${config.fraggle.provider.type}")
        println("URL: ${config.fraggle.provider.url}")
        println("Model: ${config.fraggle.provider.model.ifBlank { "(default)" }}")
        println()

        // Create and initialize orchestrator
        val orchestrator = ServiceOrchestrator(config)
        orchestrator.initialize()

        println("Available skills: ${orchestrator.getSkills().all().map { it.name }}")
        println()

        // Interactive loop
        val chatId = "interactive-${System.currentTimeMillis()}"
        val senderId = "local-user"

        while (true) {
            print("You: ")
            val input = readlnOrNull() ?: break

            if (input.lowercase() in listOf("quit", "exit", "q")) {
                println("Goodbye!")
                break
            }

            if (input.isBlank()) continue

            try {
                val response = orchestrator.processMessage(
                    chatId = chatId,
                    senderId = senderId,
                    senderName = "User",
                    text = input,
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

    private fun loadConfig(): FraggleConfig {
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
        return if (model != null) {
            config.copy(
                fraggle = config.fraggle.copy(
                    provider = config.fraggle.provider.copy(model = model!!)
                )
            )
        } else {
            config
        }
    }
}

/**
 * Test Signal connection.
 */
class TestSignalCommand : CliktCommand(name = "test-signal") {
    private val logger = LoggerFactory.getLogger(TestSignalCommand::class.java)

    private val configPath by option(
        "-c", "--config",
        help = "Path to configuration file (default: \$FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    override fun run() = runBlocking {
        println("Testing Signal connection...")
        println("FRAGGLE_ROOT: ${FraggleEnvironment.root}")

        // Load configuration
        val path = if (configPath != null) {
            Path(configPath!!).toAbsolutePath()
        } else {
            FraggleEnvironment.defaultConfigPath
        }

        val config = if (path.exists()) {
            ConfigLoader.load(path)
        } else {
            println("Configuration file not found: $path")
            return@runBlocking
        }

        // Get Signal bridge config
        val signalBridgeConfig = config.fraggle.bridges.signal

        if (signalBridgeConfig == null || !signalBridgeConfig.enabled || signalBridgeConfig.phone.isBlank()) {
            println("Error: No phone number configured for Signal.")
            println("Please configure fraggle.bridges.signal in your configuration file.")
            return@runBlocking
        }

        println("Phone number: ${signalBridgeConfig.phone}")
        println("Config dir: ${signalBridgeConfig.configDir}")
        println("Trigger: ${signalBridgeConfig.trigger ?: "(none)"}")

        // Try to connect
        try {
            val signalConfig = org.drewcarlson.fraggle.signal.SignalConfig(
                phoneNumber = signalBridgeConfig.phone,
                configDir = signalBridgeConfig.configDir,
                triggerPrefix = signalBridgeConfig.trigger,
                signalCliPath = signalBridgeConfig.signalCliPath,
            )

            val bridge = org.drewcarlson.fraggle.signal.SignalBridge(signalConfig)
            println("\nConnecting to Signal...")
            bridge.connect()

            println("Connected successfully!")
            println("Listening for messages for 10 seconds...")

            // Listen for a short time
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                bridge.messages().collect { message ->
                    println("Received: ${message.content} from ${message.sender.id}")
                }
            }

            bridge.disconnect()
            println("\nTest complete.")
        } catch (e: Exception) {
            println("\nError connecting to Signal: ${e.message}")
            println("\nMake sure:")
            println("  1. signal-cli is installed and in your PATH")
            println("  2. signal-cli is registered with the phone number")
            println("  3. The config directory exists and is readable")
        }
    }
}
