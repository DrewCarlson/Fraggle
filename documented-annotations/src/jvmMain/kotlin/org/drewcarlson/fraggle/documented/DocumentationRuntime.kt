package org.drewcarlson.fraggle.documented

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Documentation for a class annotated with @Documented.
 * JVM-only: uses reflection.
 */
interface ClassDocumentation<T : Any> {
    /** The documented class type */
    val klass: KClass<T>

    /** Human-readable display name */
    val name: String

    /** Description of the class/section */
    val description: String

    /** Documented properties in this class */
    val properties: List<PropertyDocumentation<T, *>>

    /** Nested documented classes (properties whose types are also @Documented) */
    val nestedClasses: List<NestedClassDocumentation<T, *>>

    /**
     * Convert to serializable info for API responses.
     */
    fun toInfo(instance: T? = null): ClassDocumentationInfo {
        return ClassDocumentationInfo(
            className = klass.simpleName ?: klass.toString(),
            name = name,
            description = description,
            properties = properties.map { it.toInfo(instance) },
            nestedClasses = nestedClasses.map { nested ->
                @Suppress("UNCHECKED_CAST")
                val typedNested = nested as NestedClassDocumentation<T, Any>
                val nestedInstance = instance?.let { typedNested.getInstance(it) }
                typedNested.toInfoUntyped(nestedInstance)
            },
        )
    }
}

/**
 * Documentation for a property annotated with @Documented.
 * JVM-only: uses reflection.
 */
interface PropertyDocumentation<T : Any, V> {
    /** The property name in code */
    val propertyName: String

    /** Human-readable display name */
    val name: String

    /** Description of the property */
    val description: String

    /** The property reference for accessing the value */
    val property: KProperty1<T, V>

    /** Whether this property's type is also a @Documented class */
    val isNestedDocumented: Boolean

    /** Get the value from an instance */
    fun getValue(instance: T): V = property.get(instance)

    /**
     * Convert to serializable info.
     */
    fun toInfo(instance: T? = null): PropertyDocumentationInfo {
        val value = instance?.let { getValue(it) }
        val valueType = determineValueType(value)
        val enumValues = if (valueType == DocumentedValueType.ENUM) {
            getEnumValues()
        } else null

        return PropertyDocumentationInfo(
            propertyName = propertyName,
            name = name,
            description = description,
            valueType = valueType,
            isNullable = property.returnType.isMarkedNullable,
            enumValues = enumValues,
            currentValue = value?.toString(),
        )
    }

    private fun determineValueType(value: Any?): DocumentedValueType {
        return when {
            property.returnType.isMarkedNullable && value == null -> DocumentedValueType.NULLABLE
            isNestedDocumented -> DocumentedValueType.NESTED_OBJECT
            value is String -> DocumentedValueType.STRING
            value is Int -> DocumentedValueType.INT
            value is Long -> DocumentedValueType.LONG
            value is Double || value is Float -> DocumentedValueType.DOUBLE
            value is Boolean -> DocumentedValueType.BOOLEAN
            value is Enum<*> -> DocumentedValueType.ENUM
            value is List<*> -> DocumentedValueType.LIST
            else -> DocumentedValueType.UNKNOWN
        }
    }

    private fun getEnumValues(): List<String>? {
        val classifier = property.returnType.classifier as? KClass<*> ?: return null
        if (!classifier.java.isEnum) return null
        return classifier.java.enumConstants?.map { it.toString() }
    }
}

/**
 * Documentation for a nested @Documented class within a parent class.
 * JVM-only: uses reflection.
 */
interface NestedClassDocumentation<P : Any, C : Any> {
    /** The property in the parent class that holds this nested class */
    val property: KProperty1<P, C?>

    /** The property name in code */
    val propertyName: String

    /** Documentation for the nested class */
    val documentation: ClassDocumentation<C>

    /** Get the nested instance from the parent (may be null for optional nested classes) */
    fun getInstance(parent: P): C? = property.get(parent)

    /**
     * Convert to serializable info.
     */
    fun toInfo(instance: C? = null): NestedClassDocumentationInfo {
        return NestedClassDocumentationInfo(
            propertyName = propertyName,
            isNullable = property.returnType.isMarkedNullable,
            documentation = documentation.toInfo(instance),
        )
    }

    /**
     * Convert to serializable info without type checking (for internal use).
     */
    fun toInfoUntyped(instance: Any? = null): NestedClassDocumentationInfo {
        @Suppress("UNCHECKED_CAST")
        return toInfo(instance as? C)
    }
}

/**
 * Registry of all documented classes, used for runtime lookup.
 * JVM-only: uses reflection.
 */
object DocumentationRegistry {
    private val registry = mutableMapOf<KClass<*>, ClassDocumentation<*>>()

    /**
     * Register documentation for a class.
     */
    fun <T : Any> register(klass: KClass<T>, documentation: ClassDocumentation<T>) {
        registry[klass] = documentation
    }

    /**
     * Get documentation for a class, if available.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(klass: KClass<T>): ClassDocumentation<T>? {
        return registry[klass] as? ClassDocumentation<T>
    }

    /**
     * Get all registered documentation.
     */
    fun all(): Collection<ClassDocumentation<*>> = registry.values

    /**
     * Clear the registry (useful for testing).
     */
    fun clear() {
        registry.clear()
    }
}

/**
 * Extension function to get documentation for a class.
 */
inline fun <reified T : Any> documentationOf(): ClassDocumentation<T>? {
    return DocumentationRegistry.get(T::class)
}
