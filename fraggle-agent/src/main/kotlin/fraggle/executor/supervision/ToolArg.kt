package fraggle.executor.supervision

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Annotates a tool `Args` property to indicate how its value should be
 * interpreted during policy evaluation.
 *
 * This is a [SerialInfo] annotation, so it is preserved in the generated
 * `SerialDescriptor` and accessible at runtime via
 * `descriptor.getElementAnnotations(i)` — no kotlin-reflect needed.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ToolArg(val kind: ToolArgKind)

/**
 * Describes how a tool argument's value should be matched against policy patterns.
 */
enum class ToolArgKind {
    /** Normalize path via `Path.of(v).normalize()`, then glob-match against patterns. */
    PATH,
    /** Parse as shell command string via [ShellCommandParser], match each command against patterns. */
    SHELL_COMMAND,
}

/**
 * Maps tool names to their argument kind annotations.
 *
 * Structure: `{ "tool_name" -> { "arg_name" -> ToolArgKind } }`
 *
 * Only arguments that have a `@ToolArg` annotation are included.
 */
data class ToolArgTypes(val types: Map<String, Map<String, ToolArgKind>>)
