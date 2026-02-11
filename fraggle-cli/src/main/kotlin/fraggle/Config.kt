package fraggle

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import fraggle.models.FraggleConfig
import fraggle.models.RegisteredChatConfig
import fraggle.signal.RegisteredChat
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
