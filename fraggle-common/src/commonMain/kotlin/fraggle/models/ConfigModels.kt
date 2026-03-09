package fraggle.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import fraggle.documented.Documented

/**
 * Root configuration for Fraggle.
 * This is the shared model used across JVM and JS targets.
 */
@Serializable
@Documented(name = "Fraggle Configuration", description = "Root configuration for the Fraggle application")
data class FraggleConfig(
    @Documented(name = "Settings", description = "Main application settings")
    val fraggle: FraggleSettings = FraggleSettings(),
)

@Serializable
@Documented(name = "Settings", description = "Main Fraggle settings containing all configuration sections")
data class FraggleSettings(
    @Documented(name = "Provider", description = "LLM provider configuration")
    val provider: ProviderConfig = ProviderConfig(),
    @Documented(name = "Bridges", description = "Chat bridge configurations")
    val bridges: BridgesConfig = BridgesConfig(),
    @Documented(name = "Prompts", description = "Prompt file configuration")
    val prompts: PromptsConfig = PromptsConfig(),
    @Documented(name = "Memory", description = "Memory storage configuration")
    val memory: MemoryConfig = MemoryConfig(),
    @Documented(name = "Executor", description = "Tool execution configuration")
    val executor: ExecutorConfig = ExecutorConfig(),
    @Documented(name = "Agent", description = "AI agent behavior configuration")
    val agent: AgentConfig = AgentConfig(),
    @Documented(name = "Chats", description = "Registered chat configuration")
    val chats: ChatsConfig = ChatsConfig(),
    @Documented(name = "Web", description = "Web browsing configuration")
    val web: WebConfig = WebConfig(),
    @Documented(name = "API", description = "REST API server configuration")
    val api: ApiConfig = ApiConfig(),
    @Documented(name = "Dashboard", description = "Web dashboard configuration")
    val dashboard: DashboardConfig = DashboardConfig(),
    @Documented(name = "Database", description = "Database configuration for persistent storage")
    val database: DatabaseConfig = DatabaseConfig(),
    @Documented(name = "Tracing", description = "Agent tracing configuration")
    val tracing: TracingConfig = TracingConfig(),
)

@Serializable
@Documented(
    name = "Provider",
    description = "Configuration for the LLM provider",
    extras = ["icon=bi-cpu"],
)
data class ProviderConfig(
    @Documented(name = "Type", description = "The type of LLM provider (lmstudio, openai, anthropic)")
    val type: ProviderType = ProviderType.LMSTUDIO,
    @Documented(name = "URL", description = "The API endpoint URL for the provider")
    val url: String = "http://localhost:1234/v1",
    @Documented(name = "Model", description = "The model identifier to use (empty for provider default)")
    val model: String = "",
    @SerialName("api_key")
    @Documented(name = "API Key", description = "API key for authentication with the provider", secret = true)
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
@Documented(
    name = "Bridges",
    description = "Configuration for chat platform bridges",
    extras = ["icon=bi-plug"],
)
data class BridgesConfig(
    @Documented(name = "Signal", description = "Signal messenger bridge configuration")
    val signal: SignalBridgeConfig? = null,
    @Documented(name = "Discord", description = "Discord bot bridge configuration")
    val discord: DiscordBridgeConfig? = null,
)

/**
 * Signal bridge configuration.
 */
@Serializable
@Documented(
    name = "Signal",
    description = "Configuration for Signal messenger integration",
    extras = ["icon=bi-chat-left-text"],
)
data class SignalBridgeConfig(
    @Documented(name = "Phone", description = "Phone number registered with Signal (including country code)")
    val phone: String = "",

    @Documented(name = "Enabled", description = "Whether this bridge is enabled")
    val enabled: Boolean = phone.isNotBlank(),

    @SerialName("config_dir")
    @Documented(name = "Config Directory", description = "Directory where Signal configuration is stored")
    val configDir: String = "./config/app/signal",

    @Documented(name = "Trigger", description = "Trigger prefix for group messages (e.g., '@fraggle'). Set to null to respond to all messages")
    val trigger: String? = "@fraggle",

    @SerialName("signal_cli_path")
    @Documented(name = "Signal CLI Path", description = "Path to signal-cli executable. If null, uses system PATH or auto-installed version")
    val signalCliPath: String? = null,

    @SerialName("auto_install")
    @Documented(name = "Auto Install", description = "Whether to automatically download and install signal-cli if not found in PATH")
    val autoInstall: Boolean = true,

    @SerialName("signal_cli_version")
    @Documented(name = "Signal CLI Version", description = "Version of signal-cli to auto-install")
    val signalCliVersion: String = "0.13.23",

    @SerialName("respond_to_direct_messages")
    @Documented(name = "Respond to DMs", description = "Whether to respond to direct messages without a trigger")
    val respondToDirectMessages: Boolean = true,

    @SerialName("show_typing_indicator")
    @Documented(name = "Typing Indicator", description = "Whether to show typing indicator while processing")
    val showTypingIndicator: Boolean = true,
)

/**
 * Discord bridge configuration.
 */
@Serializable
@Documented(
    name = "Discord",
    description = "Configuration for Discord bot integration",
    extras = ["icon=bi-discord"],
)
data class DiscordBridgeConfig(
    @Documented(name = "Token", description = "Discord bot token from the Developer Portal", secret = true)
    val token: String = "",

    @Documented(name = "Enabled", description = "Whether this bridge is enabled")
    val enabled: Boolean = token.isNotBlank(),

    @SerialName("client_id")
    @Documented(name = "Client ID", description = "Application client ID for OAuth2 (optional, extracted from token if not set)")
    val clientId: String? = null,

    @SerialName("client_secret")
    @Documented(name = "Client Secret", description = "Application client secret for OAuth2 user installation flow", secret = true)
    val clientSecret: String? = null,

    @SerialName("oauth_redirect_uri")
    @Documented(name = "OAuth Redirect URI", description = "OAuth2 callback URL (must match Discord Developer Portal settings)")
    val oauthRedirectUri: String? = null,

    @Documented(name = "Trigger", description = "Trigger prefix for guild messages (e.g., '!fraggle'). Set to null to respond to all messages")
    val trigger: String? = "!fraggle",

    @SerialName("respond_to_direct_messages")
    @Documented(name = "Respond to DMs", description = "Whether to respond to direct messages without a trigger")
    val respondToDirectMessages: Boolean = true,

    @SerialName("show_typing_indicator")
    @Documented(name = "Typing Indicator", description = "Whether to show typing indicator while processing")
    val showTypingIndicator: Boolean = true,

    @SerialName("max_images_per_message")
    @Documented(name = "Max Images", description = "Maximum number of images to attach per message (Discord allows up to 10)")
    val maxImagesPerMessage: Int = 10,

    @SerialName("max_file_size_mb")
    @Documented(name = "Max File Size (MB)", description = "Maximum file size in MB (10 free, 50 Nitro Basic, 500 Nitro)")
    val maxFileSizeMb: Int = 10,

    @SerialName("allowed_guild_ids")
    @Documented(name = "Allowed Guilds", description = "List of guild IDs the bot can operate in (empty = all guilds)")
    val allowedGuildIds: List<String> = emptyList(),

    @SerialName("allowed_channel_ids")
    @Documented(name = "Allowed Channels", description = "List of channel IDs the bot can respond in (empty = all channels)")
    val allowedChannelIds: List<String> = emptyList(),
)

@Serializable
@Documented(
    name = "Memory",
    description = "Configuration for the memory storage system",
    extras = ["icon=bi-journal-bookmark"],
)
data class MemoryConfig(
    @SerialName("base_dir")
    @Documented(name = "Base Directory", description = "Directory where memory files are stored")
    val baseDir: String = "./data/memory",
)

/**
 * Policy for a tool policy rule or argument matcher.
 */
@Serializable
enum class ApprovalPolicy {
    @SerialName("allow")
    ALLOW,
    @SerialName("ask")
    ASK,
    @SerialName("deny")
    DENY,
}

/**
 * Structured command pattern for fine-grained shell command matching.
 *
 * Provides explicit control over flags and positional arguments instead of
 * the simple `"executable argPattern..."` string format in [ArgMatcher.value].
 *
 * @property command The executable name to match (e.g., "cat", "grep").
 * @property allowFlags Allowlist of flags. `null` means any flags are OK; empty list means no flags allowed.
 * @property denyFlags Denylist of flags. Checked before [allowFlags] — takes precedence.
 * @property paths Glob patterns for path-like positional arguments (normalized before matching).
 * @property args Glob patterns for non-path positional arguments (plain match, no normalization).
 */
@Serializable
data class CommandPattern(
    val command: String,
    @SerialName("allow_flags")
    val allowFlags: List<String>? = null,
    @SerialName("deny_flags")
    val denyFlags: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val args: List<String> = emptyList(),
)

/**
 * Matches a specific named argument's value against a list of patterns.
 *
 * How [value] patterns are interpreted depends on the `@ToolArg` annotation
 * on the corresponding tool `Args` property:
 * - `@ToolArg(PATH)`: patterns are glob-matched against the normalized path
 * - `@ToolArg(SHELL_COMMAND)`: patterns are shell-aware command patterns
 *   (simple string patterns via [value], or structured [CommandPattern] via [commands])
 * - No annotation: patterns are plain glob-matched against the raw string value
 *
 * For shell commands, [commands] takes precedence over [value] when non-empty.
 *
 * An optional [policy] overrides the tool-level policy for this argument.
 */
@Serializable
data class ArgMatcher(
    val name: String,
    val value: List<String> = emptyList(),
    val commands: List<CommandPattern> = emptyList(),
    val policy: ApprovalPolicy? = null,
)

/**
 * A tool policy rule matching a tool name with optional argument matchers and policy.
 *
 * Rules are evaluated top-to-bottom, first match wins. The effective policy is
 * determined by the most restrictive arg-level policy (deny > ask > allow),
 * falling back to the tool-level [policy] (default: ALLOW).
 */
@Serializable
data class ToolPolicy(
    val tool: String,
    val policy: ApprovalPolicy = ApprovalPolicy.ALLOW,
    val args: List<ArgMatcher> = emptyList(),
)

@Serializable
@Documented(
    name = "Executor",
    description = "Configuration for tool execution",
    extras = ["icon=bi-shield-check"],
)
data class ExecutorConfig(
    @Documented(name = "Type", description = "Executor type (local, remote)")
    val type: ExecutorType = ExecutorType.LOCAL,
    @SerialName("work_dir")
    @Documented(name = "Work Directory", description = "Working directory for tool execution")
    val workDir: String = "./data/workspace",
    @SerialName("remote_url")
    @Documented(name = "Remote URL", description = "URL of the remote worker process (only used when type is remote)")
    val remoteUrl: String = "",
    @Documented(name = "Supervision", description = "Tool supervision mode (none, supervised)")
    val supervision: SupervisionMode = SupervisionMode.NONE,
    @SerialName("tool_policies")
    @Documented(name = "Tool Policies", description = "Policy-based rules for tool approval (supports allow, deny, ask policies with argument matchers)")
    val toolPolicies: List<ToolPolicy> = emptyList(),
    @SerialName("bridge_approval")
    @Documented(name = "Bridge Approval", description = "Whether to forward tool permission requests to chat bridges for approval (always available in chat mode and dashboard)")
    val bridgeApproval: Boolean = true,
)

enum class ExecutorType {
    @SerialName("local")
    LOCAL,
    @SerialName("remote")
    REMOTE,
}

@Serializable
enum class SupervisionMode {
    @SerialName("none")
    NONE,
    @SerialName("supervised")
    SUPERVISED,
}

@Serializable
@Documented(
    name = "Agent",
    description = "Configuration for AI agent behavior",
    extras = ["icon=bi-robot"],
)
data class AgentConfig(
    @Documented(name = "Temperature", description = "Sampling temperature for LLM responses (0.0-2.0)")
    val temperature: Double = 0.7,
    @SerialName("max_tokens")
    @Documented(name = "Max Tokens", description = "Maximum number of tokens in LLM responses")
    val maxTokens: Long = 4096,
    @SerialName("max_iterations")
    @Documented(name = "Max Iterations", description = "Maximum number of tool-use iterations per request")
    val maxIterations: Int = 10,
    @SerialName("max_history_messages")
    @Documented(name = "Max History", description = "Maximum number of messages to include in conversation history")
    val maxHistoryMessages: Int = 20,
    @Documented(name = "Vision", description = "Whether to pass images from chat messages to the LLM for vision analysis (requires a vision-capable model)")
    val vision: Boolean = false,
)

@Serializable
@Documented(
    name = "Prompts",
    description = "Configuration for prompt file management",
    extras = ["icon=bi-file-text"],
)
data class PromptsConfig(
    @SerialName("prompts_dir")
    @Documented(name = "Prompts Directory", description = "Directory where prompt files (SYSTEM.md, IDENTITY.md, USER.md) are stored")
    val promptsDir: String = "./config/prompts",

    @SerialName("max_file_chars")
    @Documented(name = "Max File Chars", description = "Maximum characters to include from each prompt file")
    val maxFileChars: Int = 20_000,

    @SerialName("auto_create_missing")
    @Documented(name = "Auto Create", description = "Whether to auto-create missing prompt files from templates")
    val autoCreateMissing: Boolean = true,
)

@Serializable
@Documented(
    name = "Chats",
    description = "Configuration for registered chats",
    extras = ["icon=bi-chat-dots"],
)
data class ChatsConfig(
    @Documented(name = "Registered", description = "List of registered chat configurations")
    val registered: List<RegisteredChatConfig> = emptyList(),
)

@Serializable
@Documented(
    name = "Registered Chat",
    description = "Configuration for a specific registered chat",
    extras = ["icon=bi-person-badge"],
)
data class RegisteredChatConfig(
    @Documented(name = "ID", description = "Unique identifier for the chat")
    val id: String,
    @Documented(name = "Name", description = "Human-readable name for the chat")
    val name: String? = null,
    @SerialName("trigger_override")
    @Documented(name = "Trigger Override", description = "Override the default trigger for this specific chat")
    val triggerOverride: String? = null,
    @Documented(name = "Enabled", description = "Whether this chat is enabled")
    val enabled: Boolean = true,
)

@Serializable
@Documented(
    name = "Web",
    description = "Configuration for web browsing capabilities",
    extras = ["icon=bi-globe"],
)
data class WebConfig(
    @Documented(name = "Playwright", description = "Playwright browser automation configuration")
    val playwright: PlaywrightConfig? = null,
)

@Serializable
@Documented(
    name = "Playwright",
    description = "Configuration for Playwright browser automation",
    extras = ["icon=bi-browser-chrome"],
)
data class PlaywrightConfig(
    @SerialName("ws_endpoint")
    @Documented(name = "WebSocket Endpoint", description = "WebSocket URL for connecting to a Playwright browser server")
    val wsEndpoint: String,

    @SerialName("navigation_timeout")
    @Documented(name = "Navigation Timeout", description = "Timeout in milliseconds for page navigation")
    val navigationTimeout: Long = 30_000,

    @SerialName("wait_after_load")
    @Documented(name = "Wait After Load", description = "Time to wait after page load for JavaScript to execute (ms)")
    val waitAfterLoad: Long = 2_000,

    @SerialName("viewport_width")
    @Documented(name = "Viewport Width", description = "Browser viewport width in pixels")
    val viewportWidth: Int = 1280,

    @SerialName("viewport_height")
    @Documented(name = "Viewport Height", description = "Browser viewport height in pixels")
    val viewportHeight: Int = 720,

    @SerialName("user_agent")
    @Documented(name = "User Agent", description = "Custom user agent string (null for browser default)")
    val userAgent: String? = null,
)

/**
 * REST API server configuration.
 */
@Serializable
@Documented(
    name = "API Server",
    description = "Configuration for the REST API server",
    extras = ["icon=bi-hdd-network"],
)
data class ApiConfig(
    @Documented(name = "Enabled", description = "Whether the API server is enabled")
    val enabled: Boolean = false,

    @Documented(name = "Host", description = "Host address to bind the API server to")
    val host: String = "0.0.0.0",

    @Documented(name = "Port", description = "Port number for the API server")
    val port: Int = 9191,

    @Documented(name = "CORS", description = "Cross-Origin Resource Sharing configuration")
    val cors: CorsConfig = CorsConfig(),
)

/**
 * CORS settings for the API.
 */
@Serializable
@Documented(
    name = "CORS",
    description = "Cross-Origin Resource Sharing settings",
    extras = ["icon=bi-shield"],
)
data class CorsConfig(
    @Documented(name = "Enabled", description = "Whether CORS is enabled")
    val enabled: Boolean = true,

    @SerialName("allowed_origins")
    @Documented(name = "Allowed Origins", description = "List of allowed origins for CORS requests")
    val allowedOrigins: List<String> = emptyList(),
)

/**
 * Dashboard web UI configuration.
 */
@Serializable
@Documented(
    name = "Dashboard",
    description = "Configuration for the web dashboard UI",
    extras = ["icon=bi-window"],
)
data class DashboardConfig(
    @Documented(name = "Enabled", description = "Whether the dashboard is enabled (requires API to be enabled)")
    val enabled: Boolean = false,

    @SerialName("static_path")
    @Documented(name = "Static Path", description = "Path to built dashboard static files (null for embedded)")
    val staticPath: String? = null,
)

/**
 * Database configuration for persistent storage.
 */
@Serializable
@Documented(
    name = "Database",
    description = "Configuration for the SQLite database used for chat history and metadata",
    extras = ["icon=bi-database"],
)
data class DatabaseConfig(
    @Documented(name = "Path", description = "Path to the SQLite database file")
    val path: String = "./config/fraggle.db",
)

@Serializable
enum class TracingLevel {
    @SerialName("off")
    OFF,
    @SerialName("metadata")
    METADATA,
    @SerialName("full")
    FULL,
}

@Serializable
@Documented(
    name = "Tracing",
    description = "Configuration for agent tracing and observability",
    extras = ["icon=bi-activity"],
)
data class TracingConfig(
    @Documented(name = "Level", description = "Tracing level: off (disabled), metadata (events without LLM content), full (everything)")
    val level: TracingLevel = TracingLevel.OFF,
)
