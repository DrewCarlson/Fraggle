package fraggle.documented

/**
 * Marks a class or property as documented with a human-readable name and description.
 *
 * When applied to a class, it documents the entire configuration section.
 * When applied to a property, it documents that specific field.
 *
 * Example:
 * ```kotlin
 * @Documented(
 *     name = "Provider",
 *     description = "LLM provider configuration",
 *     extras = ["icon=bi-cpu"]
 * )
 * data class ProviderConfig(
 *     @Documented(name = "Type", description = "The type of LLM provider")
 *     val type: ProviderType,
 *     @Documented(name = "API Key", description = "API key for auth", secret = true)
 *     val apiKey: String?,
 * )
 * ```
 *
 * @param name The human-readable display name for this class or property
 * @param description An optional description explaining the purpose or usage
 * @param secret Whether this field contains sensitive data that should be masked in UIs
 * @param extras Additional key-value pairs in "key=value" format for UI hints (e.g., "icon=bi-cpu")
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Documented(
    val name: String,
    val description: String = "",
    val secret: Boolean = false,
    val extras: Array<String> = [],
)
