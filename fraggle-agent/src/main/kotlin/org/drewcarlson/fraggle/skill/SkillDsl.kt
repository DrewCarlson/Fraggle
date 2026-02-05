package org.drewcarlson.fraggle.skill

/**
 * DSL for building type-safe skills.
 *
 * Example usage:
 * ```kotlin
 * val fileReadSkill = skill("file_read") {
 *     description = "Read contents of a file"
 *
 *     parameter<String>("path") {
 *         description = "File path to read"
 *         required = true
 *         validate { it.isNotBlank() && !it.contains("..") }
 *     }
 *
 *     parameter<Int>("maxLines") {
 *         description = "Maximum lines to read"
 *         default = 1000
 *     }
 *
 *     execute { params ->
 *         val path = params.get<String>("path")
 *         val maxLines = params.get<Int>("maxLines")
 *         SkillResult.Success("File contents here")
 *     }
 * }
 * ```
 */
fun skill(name: String, block: SkillBuilder.() -> Unit): Skill {
    val builder = SkillBuilder(name)
    builder.block()
    return builder.build()
}

@DslMarker
annotation class SkillDsl

@SkillDsl
class SkillBuilder(private val name: String) {
    var description: String = ""
    @PublishedApi
    internal val parameters = mutableListOf<SkillParameter<*>>()
    private var executor: (suspend (SkillParameters) -> SkillResult)? = null

    inline fun <reified T : Any> parameter(
        name: String,
        block: ParameterBuilder<T>.() -> Unit = {},
    ) {
        val builder = ParameterBuilder<T>(name, inferParameterType<T>())
        builder.block()
        parameters.add(builder.build())
    }

    fun execute(block: suspend (SkillParameters) -> SkillResult) {
        executor = block
    }

    fun build(): Skill {
        require(description.isNotBlank()) { "Skill description is required" }
        requireNotNull(executor) { "Skill executor is required" }

        return Skill(
            name = name,
            description = description,
            parameters = parameters.toList(),
            executor = executor!!,
        )
    }
}

@SkillDsl
class ParameterBuilder<T : Any>(
    private val name: String,
    private val type: ParameterType,
) {
    var description: String = ""
    var required: Boolean = false
    var default: T? = null
    private var validator: ((T) -> Boolean)? = null

    fun validate(block: (T) -> Boolean) {
        validator = block
    }

    fun build(): SkillParameter<T> {
        return SkillParameter(
            name = name,
            description = description,
            type = type,
            required = required,
            default = default,
            validator = validator,
        )
    }
}

inline fun <reified T : Any> inferParameterType(): ParameterType {
    return when (T::class) {
        String::class -> ParameterType.StringType
        Int::class -> ParameterType.IntType
        Long::class -> ParameterType.LongType
        Double::class -> ParameterType.DoubleType
        Boolean::class -> ParameterType.BooleanType
        else -> throw IllegalArgumentException("Unsupported parameter type: ${T::class}")
    }
}

/**
 * Create a string array parameter type.
 */
fun stringArrayParameter(
    name: String,
    description: String,
    required: Boolean = false,
): SkillParameter<List<String>> {
    return SkillParameter(
        name = name,
        description = description,
        type = ParameterType.StringArrayType,
        required = required,
        default = null,
        validator = null,
    )
}

/**
 * Create an enum parameter type.
 */
fun enumParameter(
    name: String,
    description: String,
    values: List<String>,
    required: Boolean = false,
    default: String? = null,
): SkillParameter<String> {
    return SkillParameter(
        name = name,
        description = description,
        type = ParameterType.EnumType(values),
        required = required,
        default = default,
        validator = { it in values },
    )
}
