package org.drewcarlson.fraggle

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import org.drewcarlson.fraggle.documented.ClassDocumentationInfo
import org.drewcarlson.fraggle.documented.generated.ConfigDocumentation
import org.drewcarlson.fraggle.models.*
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Interactive configuration wizard for Fraggle.
 */
class ConfigureCommand : CliktCommand(name = "configure") {
    private val configPath by option(
        "-c", "--config",
        help = "Path to configuration file (default: \$FRAGGLE_ROOT/config/fraggle.yaml)"
    )

    override fun run() {
        println()
        println("Fraggle Configuration Wizard")
        println("============================")
        println()

        val path = if (configPath != null) {
            Path(configPath!!).toAbsolutePath()
        } else {
            FraggleEnvironment.defaultConfigPath
        }

        println("Configuration file: $path")
        println()

        // Check if config already exists
        val existingConfig = if (path.exists()) {
            try {
                ConfigLoader.load(path)
            } catch (e: Exception) {
                println("Warning: Could not parse existing configuration: ${e.message}")
                null
            }
        } else {
            null
        }

        val baseConfig: FraggleConfig
        if (existingConfig != null) {
            println("An existing configuration was found.")
            print("Do you want to update it? (y/n) [y]: ")
            val response = readlnOrNull()?.trim()?.lowercase() ?: "y"

            if (response != "y" && response != "yes" && response.isNotEmpty()) {
                println("Configuration cancelled.")
                return
            }
            baseConfig = existingConfig
            println()
        } else {
            baseConfig = FraggleConfig()
        }

        // Ask for setup mode
        println("Choose setup mode:")
        println("  1. Quick setup - Configure essential settings only")
        println("  2. Full setup  - Walk through all configuration sections")
        println()
        print("Enter choice [1]: ")
        val modeChoice = readlnOrNull()?.trim() ?: "1"

        val isQuickSetup = modeChoice != "2"
        println()

        // Run the appropriate setup
        val finalConfig = if (isQuickSetup) {
            runQuickSetup(baseConfig)
        } else {
            runFullSetup(baseConfig)
        }

        if (finalConfig == null) {
            println("Configuration cancelled.")
            return
        }

        // Save configuration
        println()
        println("─".repeat(50))
        println()
        print("Save configuration to $path? (y/n) [y]: ")
        val saveResponse = readlnOrNull()?.trim()?.lowercase() ?: "y"

        if (saveResponse == "y" || saveResponse == "yes" || saveResponse.isEmpty()) {
            ConfigLoader.save(finalConfig, path)
            println()
            println("Configuration saved successfully!")
            println()
            println("Next steps:")
            println("  - Run 'fraggle run' to start the service")
            println("  - Run 'fraggle chat' to test interactively")
            if (finalConfig.fraggle.bridges.signal != null) {
                println("  - Run 'fraggle init-bridge signal' to complete Signal setup")
            }
        } else {
            println("Configuration not saved.")
        }
    }

    private fun runQuickSetup(baseConfig: FraggleConfig): FraggleConfig? {
        println("─".repeat(50))
        println("Quick Setup")
        println("─".repeat(50))
        println()

        var settings = baseConfig.fraggle

        // 1. LLM Provider
        println("== LLM Provider ==")
        println(ConfigDocumentation.providerConfig.description)
        println()

        val providerResult = configureProvider(settings.provider)
        settings = settings.copy(provider = providerResult)

        // 2. Signal Bridge
        println()
        println("== Signal Integration ==")
        println("Connect Fraggle to Signal messenger for chat integration.")
        println()

        print("Enable Signal integration? (y/n) [${if (settings.bridges.signal?.enabled == true) "y" else "n"}]: ")
        val enableSignal = readlnOrNull()?.trim()?.lowercase()
        val wantSignal = enableSignal == "y" || enableSignal == "yes" ||
            (enableSignal.isNullOrEmpty() && settings.bridges.signal?.enabled == true)

        if (wantSignal) {
            val signalResult = configureSignalBridge(settings.bridges.signal) ?: return null
            settings = settings.copy(bridges = settings.bridges.copy(signal = signalResult))
        } else {
            settings = settings.copy(bridges = settings.bridges.copy(signal = null))
        }

        // 3. API Server & Dashboard
        println()
        println("== API Server & Dashboard ==")
        println("Enable the REST API server and web dashboard for remote management.")
        println()

        print("Enable API server? (y/n) [${if (settings.api.enabled) "y" else "n"}]: ")
        val enableApi = readlnOrNull()?.trim()?.lowercase()
        val wantApi = enableApi == "y" || enableApi == "yes" ||
            (enableApi.isNullOrEmpty() && settings.api.enabled)

        if (wantApi) {
            var apiConfig = settings.api.copy(enabled = true)

            print("API port [${apiConfig.port}]: ")
            val portInput = readlnOrNull()?.trim()
            if (!portInput.isNullOrEmpty()) {
                portInput.toIntOrNull()?.let { apiConfig = apiConfig.copy(port = it) }
            }

            print("Enable web dashboard? (y/n) [${if (settings.dashboard.enabled) "y" else "n"}]: ")
            val enableDash = readlnOrNull()?.trim()?.lowercase()
            val wantDash = enableDash == "y" || enableDash == "yes" ||
                (enableDash.isNullOrEmpty() && settings.dashboard.enabled)

            settings = settings.copy(
                api = apiConfig,
                dashboard = settings.dashboard.copy(enabled = wantDash)
            )
        } else {
            settings = settings.copy(
                api = settings.api.copy(enabled = false),
                dashboard = settings.dashboard.copy(enabled = false)
            )
        }

        return baseConfig.copy(fraggle = settings)
    }

    private fun runFullSetup(baseConfig: FraggleConfig): FraggleConfig? {
        println("─".repeat(50))
        println("Full Setup")
        println("─".repeat(50))
        println()
        println("Walk through each configuration section.")
        println("Press Enter to keep current/default values.")
        println()

        var settings = baseConfig.fraggle

        // 1. Provider
        printSectionHeader(ConfigDocumentation.providerConfig)
        val providerResult = configureProvider(settings.provider)
        settings = settings.copy(provider = providerResult)

        // 2. Signal Bridge
        println()
        printSectionHeader(ConfigDocumentation.signalBridgeConfig)
        print("Enable Signal integration? (y/n) [${if (settings.bridges.signal?.enabled == true) "y" else "n"}]: ")
        val enableSignal = readlnOrNull()?.trim()?.lowercase()
        val wantSignal = enableSignal == "y" || enableSignal == "yes" ||
            (enableSignal.isNullOrEmpty() && settings.bridges.signal?.enabled == true)

        if (wantSignal) {
            val signalResult = configureSignalBridge(settings.bridges.signal) ?: return null
            settings = settings.copy(bridges = settings.bridges.copy(signal = signalResult))
        }

        // 3. Memory
        println()
        printSectionHeader(ConfigDocumentation.memoryConfig)
        var memoryConfig = settings.memory
        print("Memory base directory [${memoryConfig.baseDir}]: ")
        val memoryDir = readlnOrNull()?.trim()
        if (!memoryDir.isNullOrEmpty()) {
            memoryConfig = memoryConfig.copy(baseDir = memoryDir)
        }
        settings = settings.copy(memory = memoryConfig)

        // 4. Executor
        println()
        printSectionHeader(ConfigDocumentation.executorConfig)
        var executorConfig = settings.executor
        println("Executor types: local, remote")
        print("Executor type [${executorConfig.type.name.lowercase()}]: ")
        val executorType = readlnOrNull()?.trim()?.lowercase()
        if (!executorType.isNullOrEmpty()) {
            try {
                executorConfig = executorConfig.copy(type = ExecutorType.valueOf(executorType.uppercase()))
            } catch (_: Exception) {
                println("Invalid executor type, keeping current value.")
            }
        }
        print("Work directory [${executorConfig.workDir}]: ")
        val execDir = readlnOrNull()?.trim()
        if (!execDir.isNullOrEmpty()) {
            executorConfig = executorConfig.copy(workDir = execDir)
        }
        if (executorConfig.type == ExecutorType.REMOTE) {
            print("Remote URL [${executorConfig.remoteUrl.ifBlank { "(not set)" }}]: ")
            val remoteUrl = readlnOrNull()?.trim()
            if (!remoteUrl.isNullOrEmpty()) {
                executorConfig = executorConfig.copy(remoteUrl = remoteUrl)
            }
        }
        println("Supervision modes: none, supervised")
        print("Supervision [${executorConfig.supervision.name.lowercase()}]: ")
        val supervisionMode = readlnOrNull()?.trim()?.lowercase()
        if (!supervisionMode.isNullOrEmpty()) {
            try {
                executorConfig = executorConfig.copy(supervision = SupervisionMode.valueOf(supervisionMode.uppercase()))
            } catch (_: Exception) {
                println("Invalid supervision mode, keeping current value.")
            }
        }
        settings = settings.copy(executor = executorConfig)

        // 5. Agent
        println()
        printSectionHeader(ConfigDocumentation.agentConfig)
        var agentConfig = settings.agent
        print("Temperature (0.0-2.0) [${agentConfig.temperature}]: ")
        val temp = readlnOrNull()?.trim()
        if (!temp.isNullOrEmpty()) {
            temp.toDoubleOrNull()?.let { agentConfig = agentConfig.copy(temperature = it) }
        }
        print("Max tokens [${agentConfig.maxTokens}]: ")
        val maxTokens = readlnOrNull()?.trim()
        if (!maxTokens.isNullOrEmpty()) {
            maxTokens.toLongOrNull()?.let { agentConfig = agentConfig.copy(maxTokens = it) }
        }
        print("Max iterations [${agentConfig.maxIterations}]: ")
        val maxIter = readlnOrNull()?.trim()
        if (!maxIter.isNullOrEmpty()) {
            maxIter.toIntOrNull()?.let { agentConfig = agentConfig.copy(maxIterations = it) }
        }
        settings = settings.copy(agent = agentConfig)

        // 6. API & Dashboard
        println()
        printSectionHeader(ConfigDocumentation.apiConfig)
        print("Enable API server? (y/n) [${if (settings.api.enabled) "y" else "n"}]: ")
        val enableApi = readlnOrNull()?.trim()?.lowercase()
        val wantApi = enableApi == "y" || enableApi == "yes" ||
            (enableApi.isNullOrEmpty() && settings.api.enabled)

        if (wantApi) {
            var apiConfig = settings.api.copy(enabled = true)
            print("API host [${apiConfig.host}]: ")
            val host = readlnOrNull()?.trim()
            if (!host.isNullOrEmpty()) {
                apiConfig = apiConfig.copy(host = host)
            }
            print("API port [${apiConfig.port}]: ")
            val port = readlnOrNull()?.trim()
            if (!port.isNullOrEmpty()) {
                port.toIntOrNull()?.let { apiConfig = apiConfig.copy(port = it) }
            }
            settings = settings.copy(api = apiConfig)

            println()
            printSectionHeader(ConfigDocumentation.dashboardConfig)
            print("Enable web dashboard? (y/n) [${if (settings.dashboard.enabled) "y" else "n"}]: ")
            val enableDash = readlnOrNull()?.trim()?.lowercase()
            val wantDash = enableDash == "y" || enableDash == "yes" ||
                (enableDash.isNullOrEmpty() && settings.dashboard.enabled)
            settings = settings.copy(dashboard = settings.dashboard.copy(enabled = wantDash))
        } else {
            settings = settings.copy(api = settings.api.copy(enabled = false))
        }

        return baseConfig.copy(fraggle = settings)
    }

    private fun configureProvider(current: ProviderConfig): ProviderConfig {
        var config = current
        val doc = ConfigDocumentation.providerConfig

        // Provider type
        val typeDoc = doc.properties.find { it.propertyName == "type" }!!
        println("${typeDoc.name}: ${typeDoc.description}")
        println("Options: ${typeDoc.enumValues?.joinToString(", ")?.lowercase()}")
        print("Provider type [${config.type.name.lowercase()}]: ")
        val typeInput = readlnOrNull()?.trim()?.lowercase()
        if (!typeInput.isNullOrEmpty()) {
            try {
                config = config.copy(type = ProviderType.valueOf(typeInput.uppercase()))
            } catch (_: Exception) {
                println("Invalid provider type, keeping current value.")
            }
        }
        println()

        // Set default URL based on provider type
        val defaultUrl = when (config.type) {
            ProviderType.LMSTUDIO -> "http://localhost:1234/v1"
            ProviderType.OPENAI -> "https://api.openai.com/v1"
            ProviderType.ANTHROPIC -> "https://api.anthropic.com"
        }
        if (config.url == current.url && config.type != current.type) {
            config = config.copy(url = defaultUrl)
        }

        // URL
        val urlDoc = doc.properties.find { it.propertyName == "url" }!!
        println("${urlDoc.name}: ${urlDoc.description}")
        print("API URL [${config.url}]: ")
        val urlInput = readlnOrNull()?.trim()
        if (!urlInput.isNullOrEmpty()) {
            config = config.copy(url = urlInput)
        }
        println()

        // Model
        val modelDoc = doc.properties.find { it.propertyName == "model" }!!
        println("${modelDoc.name}: ${modelDoc.description}")
        val modelDefault = config.model.ifEmpty { "(provider default)" }
        print("Model [$modelDefault]: ")
        val modelInput = readlnOrNull()?.trim()
        if (!modelInput.isNullOrEmpty()) {
            config = config.copy(model = modelInput)
        }
        println()

        // API Key (for OpenAI/Anthropic)
        if (config.type != ProviderType.LMSTUDIO) {
            val keyDoc = doc.properties.find { it.propertyName == "apiKey" }!!
            println("${keyDoc.name}: ${keyDoc.description}")
            val currentKey = config.apiKey?.let { "****${it.takeLast(4)}" } ?: "(not set)"
            print("API Key [$currentKey]: ")

            // Try to read without echo for sensitive input
            val keyInput = readSensitiveInput()
            if (!keyInput.isNullOrEmpty()) {
                config = config.copy(apiKey = keyInput)
            }
        }

        return config
    }

    private fun configureSignalBridge(current: SignalBridgeConfig?): SignalBridgeConfig? {
        var config = current ?: SignalBridgeConfig()
        val doc = ConfigDocumentation.signalBridgeConfig

        // Phone number
        val phoneDoc = doc.properties.find { it.propertyName == "phone" }!!
        println("${phoneDoc.name}: ${phoneDoc.description}")
        val phoneDefault = config.phone.ifEmpty { "(not set)" }
        print("Phone number [$phoneDefault]: ")
        val phoneInput = readlnOrNull()?.trim()
        if (!phoneInput.isNullOrEmpty()) {
            config = config.copy(phone = phoneInput)
        }

        if (config.phone.isBlank()) {
            println("Phone number is required for Signal integration.")
            return null
        }
        println()

        // Trigger
        val triggerDoc = doc.properties.find { it.propertyName == "trigger" }!!
        println("${triggerDoc.name}: ${triggerDoc.description}")
        val triggerDefault = config.trigger ?: "(respond to all)"
        print("Trigger prefix [$triggerDefault]: ")
        val triggerInput = readlnOrNull()?.trim()
        if (!triggerInput.isNullOrEmpty()) {
            config = config.copy(trigger = if (triggerInput == "none") null else triggerInput)
        }
        println()

        // Respond to DMs
        val dmDoc = doc.properties.find { it.propertyName == "respondToDirectMessages" }!!
        println("${dmDoc.name}: ${dmDoc.description}")
        print("Respond to direct messages? (y/n) [${if (config.respondToDirectMessages) "y" else "n"}]: ")
        val dmInput = readlnOrNull()?.trim()?.lowercase()
        if (!dmInput.isNullOrEmpty()) {
            config = config.copy(respondToDirectMessages = dmInput == "y" || dmInput == "yes")
        }

        return config.copy(enabled = true)
    }

    private fun printSectionHeader(doc: ClassDocumentationInfo) {
        val icon = doc.extras["icon"]?.let { "[$it] " } ?: ""
        println("== $icon${doc.name} ==")
        println(doc.description)
        println()
    }

    private fun readSensitiveInput(): String? {
        val console = System.console()
        return if (console != null) {
            String(console.readPassword())
        } else {
            readlnOrNull()
        }
    }
}
