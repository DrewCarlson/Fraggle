package fraggle.agent.tool

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Attach a human-readable description to a tool argument field.
 * Surfaced in the generated JSON Schema so the LLM knows what each field means.
 *
 * Usage:
 * ```
 * @Serializable
 * data class Args(
 *     @param:LLMDescription("Path to the file")
 *     val path: String,
 * )
 * ```
 *
 * Read by [FraggleToolRegistry.descriptorToJsonSchema] via reflection on the
 * annotation's `value` property (matches annotation class `simpleName`).
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class LLMDescription(val value: String)
