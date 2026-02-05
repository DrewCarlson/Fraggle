package org.drewcarlson.fraggle

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.drewcarlson.fraggle.models.FraggleConfig
import org.drewcarlson.fraggle.models.RegisteredChatConfig
import org.drewcarlson.fraggle.signal.RegisteredChat
import java.nio.file.Path
import kotlin.io.path.*

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
 * Extension to convert RegisteredChatConfig to the signal module's RegisteredChat.
 */
fun RegisteredChatConfig.toRegisteredChat(): RegisteredChat = RegisteredChat(
    id = id,
    name = name,
    triggerOverride = triggerOverride,
    enabled = enabled,
)

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

    /**
     * Save configuration to a file path.
     */
    fun save(config: FraggleConfig, path: Path) {
        path.parent?.createDirectories()
        val content = yaml.encodeToString(FraggleConfig.serializer(), config)
        path.writeText(content)
    }

    /**
     * Load configuration from path, creating default if it doesn't exist.
     */
    fun loadOrCreateDefault(path: Path): FraggleConfig {
        return if (path.exists()) {
            load(path)
        } else {
            val defaultConfig = default()
            save(defaultConfig, path)
            defaultConfig
        }
    }
}

class ConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)
