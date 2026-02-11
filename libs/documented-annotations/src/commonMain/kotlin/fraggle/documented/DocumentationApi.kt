package fraggle.documented

import kotlinx.serialization.Serializable

/**
 * Type information for documentation display.
 */
@Serializable
enum class DocumentedValueType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    ENUM,
    LIST,
    NESTED_OBJECT,
    NULLABLE,
    UNKNOWN,
}

/**
 * Serializable representation of class documentation for API responses.
 * This can be used on both JVM and JS.
 */
@Serializable
data class ClassDocumentationInfo(
    val className: String,
    val name: String,
    val description: String,
    val properties: List<PropertyDocumentationInfo>,
    val nestedClasses: List<NestedClassDocumentationInfo>,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Serializable representation of property documentation.
 */
@Serializable
data class PropertyDocumentationInfo(
    val propertyName: String,
    val name: String,
    val description: String,
    val valueType: DocumentedValueType,
    val isNullable: Boolean,
    val isSecret: Boolean = false,
    val enumValues: List<String>? = null,
    val currentValue: String? = null,
)

/**
 * Serializable representation of nested class documentation.
 */
@Serializable
data class NestedClassDocumentationInfo(
    val propertyName: String,
    val isNullable: Boolean,
    val documentation: ClassDocumentationInfo,
)
