package org.drewcarlson.fraggle.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root configuration for Fraggle.
 * This is the shared model used across JVM and JS targets.
 */
@Serializable
data class FraggleConfig(
    val fraggle: FraggleSettings = FraggleSettings(),
)

@Serializable
data class FraggleSettings(
    val provider: ProviderConfig = ProviderConfig(),
    val bridges: BridgesConfig = BridgesConfig(),
    val prompts: PromptsConfig = PromptsConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val sandbox: SandboxConfig = SandboxConfig(),
    val agent: AgentConfig = AgentConfig(),
    val chats: ChatsConfig = ChatsConfig(),
    val web: WebConfig = WebConfig(),
    val api: ApiConfig = ApiConfig(),
    val dashboard: DashboardConfig = DashboardConfig(),
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
data class MemoryConfig(
    @SerialName("base_dir")
    val baseDir: String = "./data/memory",
)

@Serializable
data class SandboxConfig(
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
data class AgentConfig(
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    @SerialName("max_iterations")
    val maxIterations: Int = 10,
    @SerialName("max_history_messages")
    val maxHistoryMessages: Int = 20,
)

@Serializable
data class PromptsConfig(
    /**
     * Directory where prompt files (SYSTEM.md, IDENTITY.md, USER.md) are stored.
     */
    @SerialName("prompts_dir")
    val promptsDir: String = "./config/prompts",

    /**
     * Maximum characters to include from each prompt file.
     */
    @SerialName("max_file_chars")
    val maxFileChars: Int = 20_000,

    /**
     * Whether to auto-create missing prompt files from templates.
     */
    @SerialName("auto_create_missing")
    val autoCreateMissing: Boolean = true,
)

@Serializable
data class ChatsConfig(
    val registered: List<RegisteredChatConfig> = emptyList(),
)

@Serializable
data class RegisteredChatConfig(
    val id: String,
    val name: String? = null,
    @SerialName("trigger_override")
    val triggerOverride: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class WebConfig(
    val playwright: PlaywrightConfig? = null,
)

@Serializable
data class PlaywrightConfig(
    /**
     * WebSocket URL for connecting to a Playwright browser server.
     */
    @SerialName("ws_endpoint")
    val wsEndpoint: String,

    /**
     * Timeout in milliseconds for page navigation.
     */
    @SerialName("navigation_timeout")
    val navigationTimeout: Long = 30_000,

    /**
     * Additional time to wait after page load for JavaScript to execute.
     */
    @SerialName("wait_after_load")
    val waitAfterLoad: Long = 2_000,

    /**
     * Default viewport width.
     */
    @SerialName("viewport_width")
    val viewportWidth: Int = 1280,

    /**
     * Default viewport height.
     */
    @SerialName("viewport_height")
    val viewportHeight: Int = 720,

    /**
     * User agent string to use. If not specified, uses browser default.
     */
    @SerialName("user_agent")
    val userAgent: String? = null,
)

/**
 * REST API server configuration.
 */
@Serializable
data class ApiConfig(
    /**
     * Whether the API server is enabled.
     */
    val enabled: Boolean = false,

    /**
     * Host to bind the API server to.
     */
    val host: String = "0.0.0.0",

    /**
     * Port for the API server.
     */
    val port: Int = 8080,

    /**
     * CORS configuration.
     */
    val cors: CorsConfig = CorsConfig(),
)

/**
 * CORS settings for the API.
 */
@Serializable
data class CorsConfig(
    /**
     * Whether CORS is enabled.
     */
    val enabled: Boolean = true,

    /**
     * Allowed origins for CORS requests.
     */
    @SerialName("allowed_origins")
    val allowedOrigins: List<String> = emptyList(),
)

/**
 * Dashboard web UI configuration.
 */
@Serializable
data class DashboardConfig(
    /**
     * Whether the dashboard is enabled.
     */
    val enabled: Boolean = false,

    /**
     * Path to the built dashboard static files.
     */
    @SerialName("static_path")
    val staticPath: String? = null,
)
