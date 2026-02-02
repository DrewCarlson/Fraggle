package org.drewcarlson.fraggle

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.drewcarlson.fraggle.signal.RegisteredChat
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Environment configuration for Fraggle runtime directories.
 */
object FraggleEnvironment {
    /**
     * The root directory for all Fraggle runtime files.
     * Set via FRAGGLE_ROOT environment variable, defaults to current directory.
     */
    val root: Path by lazy {
        val envRoot = System.getenv("FRAGGLE_ROOT")
        if (envRoot != null) {
            Path(envRoot).toAbsolutePath()
        } else {
            Path(".").toAbsolutePath()
        }
    }

    /**
     * Config directory: {FRAGGLE_ROOT}/config
     */
    val configDir: Path get() = root.resolve("config")

    /**
     * Data directory: {FRAGGLE_ROOT}/data
     */
    val dataDir: Path get() = root.resolve("data")

    /**
     * Logs directory: {FRAGGLE_ROOT}/logs
     */
    val logsDir: Path get() = root.resolve("logs")

    /**
     * Default config file path.
     */
    val defaultConfigPath: Path get() = configDir.resolve("fraggle.yaml")

    /**
     * Resolve a path relative to the root, expanding ~ for home directory.
     */
    fun resolvePath(path: String): Path {
        val expanded = path.replace("~", System.getProperty("user.home"))
        val p = Path(expanded)
        return if (p.isAbsolute) p else root.resolve(p).normalize()
    }
}

/**
 * Root configuration for Fraggle.
 */
@Serializable
data class FraggleConfig(
    val fraggle: FraggleSettings = FraggleSettings(),
)

@Serializable
data class FraggleSettings(
    val provider: ProviderConfig = ProviderConfig(),
    val bridges: BridgesConfig = BridgesConfig(),
    val memory: MemorySettings = MemorySettings(),
    val sandbox: SandboxSettings = SandboxSettings(),
    val agent: AgentSettings = AgentSettings(),
    val chats: ChatsSettings = ChatsSettings(),
    val web: WebSettings = WebSettings(),
)

@Serializable
data class ProviderConfig(
    val type: ProviderType = ProviderType.LMSTUDIO,
    val url: String = "http://localhost:1234/v1",
    val model: String = "",
    @SerialName("api_key")
    val apiKey: String? = null,
)

@Serializable
enum class ProviderType {
    @SerialName("lmstudio")
    LMSTUDIO,
    @SerialName("openai")
    OPENAI,
    @SerialName("anthropic")
    ANTHROPIC,
}

/**
 * Configuration for all chat bridges.
 */
@Serializable
data class BridgesConfig(
    /**
     * Signal messenger bridge configuration.
     */
    val signal: SignalBridgeConfig? = null,

    // Future bridges can be added here:
    // val discord: DiscordBridgeConfig? = null,
    // val telegram: TelegramBridgeConfig? = null,
    // val whatsapp: WhatsAppBridgeConfig? = null,
)

/**
 * Signal bridge configuration.
 */
@Serializable
data class SignalBridgeConfig(

    /**
     * The phone number registered with Signal (including country code).
     */
    val phone: String = "",

    /**
     * Whether this bridge is enabled.
     */
    val enabled: Boolean = phone.isNotBlank(),

    /**
     * Directory where Signal configuration is stored.
     */
    @SerialName("config_dir")
    val configDir: String = "~/.config/fraggle/signal",

    /**
     * The trigger prefix for group messages (e.g., "@fraggle").
     * Set to null to respond to all messages.
     */
    val trigger: String? = "@fraggle",

    /**
     * Path to signal-cli executable. If null, uses PATH.
     */
    @SerialName("signal_cli_path")
    val signalCliPath: String? = null,

    /**
     * Whether to respond to direct messages without a trigger.
     */
    @SerialName("respond_to_direct_messages")
    val respondToDirectMessages: Boolean = true,

    /**
     * Whether to show typing indicator while processing.
     */
    @SerialName("show_typing_indicator")
    val showTypingIndicator: Boolean = true,
)


@Serializable
data class MemorySettings(
    @SerialName("base_dir")
    val baseDir: String = "./data/memory",
)

@Serializable
data class SandboxSettings(
    val type: SandboxType = SandboxType.PERMISSIVE,
    @SerialName("work_dir")
    val workDir: String = "./data/workspace",
)

@Serializable
enum class SandboxType {
    @SerialName("permissive")
    PERMISSIVE,
    @SerialName("docker")
    DOCKER,
    @SerialName("gvisor")
    GVISOR,
}

@Serializable
data class AgentSettings(
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    @SerialName("max_iterations")
    val maxIterations: Int = 10,
    @SerialName("max_history_messages")
    val maxHistoryMessages: Int = 20,
)

@Serializable
data class ChatsSettings(
    val registered: List<RegisteredChatConfig> = emptyList(),
)

@Serializable
data class WebSettings(
    val playwright: PlaywrightSettings? = null,
)

@Serializable
data class PlaywrightSettings(
    /**
     * WebSocket URL for connecting to a Playwright browser server.
     * Example: ws://localhost:3000
     */
    @SerialName("ws_endpoint")
    val wsEndpoint: String,

    /**
     * Timeout in milliseconds for page navigation. Defaults to 30000 (30 seconds).
     */
    @SerialName("navigation_timeout")
    val navigationTimeout: Long = 30_000,

    /**
     * Additional time to wait after page load for JavaScript to execute.
     * Defaults to 2000 (2 seconds).
     */
    @SerialName("wait_after_load")
    val waitAfterLoad: Long = 2_000,

    /**
     * Default viewport width. Defaults to 1280.
     */
    @SerialName("viewport_width")
    val viewportWidth: Int = 1280,

    /**
     * Default viewport height. Defaults to 720.
     */
    @SerialName("viewport_height")
    val viewportHeight: Int = 720,

    /**
     * User agent string to use. If not specified, uses browser default.
     */
    @SerialName("user_agent")
    val userAgent: String? = null,
)

@Serializable
data class RegisteredChatConfig(
    val id: String,
    val name: String? = null,
    @SerialName("trigger_override")
    val triggerOverride: String? = null,
    val enabled: Boolean = true,
) {
    fun toRegisteredChat(): RegisteredChat = RegisteredChat(
        id = id,
        name = name,
        triggerOverride = triggerOverride,
        enabled = enabled,
    )
}

/**
 * Configuration loader.
 */
object ConfigLoader {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
        )
    )

    /**
     * Load configuration from a file path.
     */
    fun load(path: Path): FraggleConfig {
        if (!path.exists()) {
            throw ConfigurationException("Configuration file not found: $path")
        }

        return try {
            val content = path.readText()
            yaml.decodeFromString(FraggleConfig.serializer(), content)
        } catch (e: Exception) {
            throw ConfigurationException("Failed to parse configuration: ${e.message}", e)
        }
    }

    /**
     * Load configuration from a string.
     */
    fun loadFromString(content: String): FraggleConfig {
        return try {
            yaml.decodeFromString(FraggleConfig.serializer(), content)
        } catch (e: Exception) {
            throw ConfigurationException("Failed to parse configuration: ${e.message}", e)
        }
    }

    /**
     * Create default configuration.
     */
    fun default(): FraggleConfig = FraggleConfig()
}

class ConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)
