package fraggle.documented.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.writeTo
import fraggle.documented.ClassDocumentationInfo
import fraggle.documented.DocumentedValueType
import fraggle.documented.NestedClassDocumentationInfo
import fraggle.documented.PropertyDocumentationInfo

/**
 * KSP processor that generates documentation metadata for @Documented annotated classes.
 * Uses KotlinPoet for type-safe code generation.
 */
class DocumentedProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private val documentedAnnotation = "fraggle.documented.Documented"
    private val processedClasses = mutableListOf<ProcessedClass>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(documentedAnnotation)
        val unableToProcess = symbols.filterNot { it.validate() }.toList()

        val classesToProcess = symbols
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.validate() }
            .toList()

        if (classesToProcess.isEmpty()) {
            return unableToProcess
        }

        classesToProcess.forEach { classDeclaration ->
            collectClassInfo(classDeclaration)
        }

        processedClasses.forEach { processedClass ->
            generateDocumentation(resolver, processedClass)
        }

        generateRegistryFile()

        return unableToProcess
    }

    private fun collectClassInfo(classDeclaration: KSClassDeclaration) {
        val packageName = classDeclaration.packageName.asString()
        val className = classDeclaration.simpleName.asString()
        val qualifiedName = classDeclaration.qualifiedName?.asString() ?: "$packageName.$className"

        val annotation = classDeclaration.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == documentedAnnotation
        }

        val classDocName = annotation.arguments.find { it.name?.asString() == "name" }?.value as? String ?: className
        val classDocDescription = annotation.arguments.find { it.name?.asString() == "description" }?.value as? String ?: ""
        val classExtras = parseExtras(annotation.arguments.find { it.name?.asString() == "extras" }?.value)

        val documentedProperties = classDeclaration.getAllProperties()
            .filter { property ->
                property.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == documentedAnnotation
                }
            }
            .map { property ->
                val propAnnotation = property.annotations.first {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == documentedAnnotation
                }
                val propName = propAnnotation.arguments.find { it.name?.asString() == "name" }?.value as? String
                    ?: property.simpleName.asString()
                val propDescription = propAnnotation.arguments.find { it.name?.asString() == "description" }?.value as? String ?: ""
                val propSecret = propAnnotation.arguments.find { it.name?.asString() == "secret" }?.value as? Boolean ?: false

                val propertyType = property.type.resolve()
                val unwrappedType = unwrapNullableType(propertyType)
                val typeDecl = unwrappedType.declaration as? KSClassDeclaration
                val isNestedDocumented = typeDecl?.let { isDocumentedClass(it) } ?: false

                ProcessedProperty(
                    name = property.simpleName.asString(),
                    displayName = propName,
                    description = propDescription,
                    isSecret = propSecret,
                    type = propertyType,
                    isNullable = propertyType.isMarkedNullable,
                    isNestedDocumented = isNestedDocumented,
                    nestedClassName = if (isNestedDocumented) typeDecl.simpleName.asString() else null,
                    nestedClassQualifiedName = if (isNestedDocumented) typeDecl.qualifiedName?.asString() else null,
                )
            }
            .toList()

        processedClasses.add(ProcessedClass(
            packageName = packageName,
            className = className,
            qualifiedName = qualifiedName,
            displayName = classDocName,
            description = classDocDescription,
            extras = classExtras,
            properties = documentedProperties,
            containingFile = classDeclaration.containingFile!!,
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExtras(value: Any?): Map<String, String> {
        if (value == null) return emptyMap()
        val list = value as? List<String> ?: return emptyMap()
        return list.mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
    }

    private fun generateDocumentation(resolver: Resolver, processedClass: ProcessedClass) {
        val generatedClassName = "${processedClass.className}Doc"
        val modelClassName = ClassName(processedClass.packageName, processedClass.className)

        val nestedProps = processedClass.properties.filter { it.isNestedDocumented }

        val infoProperty = PropertySpec.builder("info", ClassDocumentationInfo::class)
            .initializer(buildInfoInitializer(processedClass, nestedProps))
            .build()

        val withValuesFunction = FunSpec.builder("withValues")
            .addKdoc("Get documentation with current values from an instance.")
            .addParameter("instance", modelClassName)
            .returns(ClassDocumentationInfo::class)
            .addCode(buildWithValuesCode(resolver, processedClass, nestedProps))
            .build()

        val objectSpec = TypeSpec.objectBuilder(generatedClassName)
            .addKdoc("Documentation for %L.", processedClass.className)
            .addProperty(infoProperty)
            .addFunction(withValuesFunction)
            .build()

        val fileSpec = FileSpec.builder(processedClass.packageName, generatedClassName)
            .addFileComment("Generated by DocumentedProcessor - do not edit")
            .addType(objectSpec)
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies(true, processedClass.containingFile))
    }

    private fun buildInfoInitializer(
        processedClass: ProcessedClass,
        nestedProps: List<ProcessedProperty>,
    ): CodeBlock {
        return buildCodeBlock {
            add("%T(\n", ClassDocumentationInfo::class)
            indent()
            add("className = %S,\n", processedClass.className)
            add("name = %S,\n", processedClass.displayName)
            add("description = %S,\n", processedClass.description)
            add("properties = listOf(\n")
            indent()
            processedClass.properties.forEachIndexed { index, prop ->
                val comma = if (index < processedClass.properties.size - 1) "," else ""
                add(buildPropertyInfoBlock(prop, comma))
            }
            unindent()
            add("),\n")
            add("nestedClasses = listOf(\n")
            indent()
            nestedProps.forEachIndexed { index, prop ->
                val comma = if (index < nestedProps.size - 1) "," else ""
                add(
                    "%T(propertyName = %S, isNullable = %L, documentation = %L.info)$comma\n",
                    NestedClassDocumentationInfo::class,
                    prop.name,
                    prop.isNullable,
                    "${prop.nestedClassName}Doc"
                )
            }
            unindent()
            add("),\n")
            add("extras = ")
            add(buildExtrasCodeBlock(processedClass.extras))
            add(",\n")
            unindent()
            add(")")
        }
    }

    private fun buildPropertyInfoBlock(prop: ProcessedProperty, trailingComma: String): CodeBlock {
        val valueType = determineValueType(prop)
        val enumValues = if (valueType == DocumentedValueType.ENUM) {
            getEnumValues(prop.type)
        } else {
            null
        }

        return buildCodeBlock {
            add("%T(\n", PropertyDocumentationInfo::class)
            indent()
            add("propertyName = %S,\n", prop.name)
            add("name = %S,\n", prop.displayName)
            add("description = %S,\n", prop.description)
            add("valueType = %T.%L,\n", DocumentedValueType::class, valueType.name)
            add("isNullable = %L,\n", prop.isNullable)
            add("isSecret = %L,\n", prop.isSecret)
            if (enumValues != null) {
                add("enumValues = listOf(")
                enumValues.forEachIndexed { i, v ->
                    add(if (i > 0) ", %S" else "%S", v)
                }
                add("),\n")
            } else {
                add("enumValues = null,\n")
            }
            unindent()
            add(")$trailingComma\n")
        }
    }

    private fun buildWithValuesCode(
        resolver: Resolver,
        processedClass: ProcessedClass,
        nestedProps: List<ProcessedProperty>,
    ): CodeBlock {
        return buildCodeBlock {
            add("return info.copy(\n")
            indent()
            add("properties = listOf(\n")
            indent()
            processedClass.properties.forEachIndexed { index, prop ->
                val comma = if (index < processedClass.properties.size - 1) "," else ""
                add(buildPropertyInfoWithValueBlock(resolver, prop, comma))
            }
            unindent()
            add("),\n")
            add("nestedClasses = listOf(\n")
            indent()
            nestedProps.forEachIndexed { index, prop ->
                val comma = if (index < nestedProps.size - 1) "," else ""
                val nestedDocClass = "${prop.nestedClassName}Doc"
                if (prop.isNullable) {
                    add(
                        "%T(propertyName = %S, isNullable = true, documentation = instance.%L?.let { %L.withValues(it) } ?: %L.info)$comma\n",
                        NestedClassDocumentationInfo::class,
                        prop.name,
                        prop.name,
                        nestedDocClass,
                        nestedDocClass
                    )
                } else {
                    add(
                        "%T(propertyName = %S, isNullable = false, documentation = %L.withValues(instance.%L))$comma\n",
                        NestedClassDocumentationInfo::class,
                        prop.name,
                        nestedDocClass,
                        prop.name
                    )
                }
            }
            unindent()
            add("),\n")
            unindent()
            add(")\n")
        }
    }

    private fun buildPropertyInfoWithValueBlock(
        resolver: Resolver,
        prop: ProcessedProperty,
        trailingComma: String
    ): CodeBlock {
        val valueType = determineValueType(prop)
        val enumValues = if (valueType == DocumentedValueType.ENUM) {
            getEnumValues(prop.type)
        } else {
            null
        }
        var valueExpr = "instance.${prop.name}"
        if (!prop.type.isAssignableFrom(resolver.builtIns.stringType)) {
            valueExpr += if (prop.isNullable) "?.toString()" else ".toString()"
        }

        return buildCodeBlock {
            add("%T(\n", PropertyDocumentationInfo::class)
            indent()
            add("propertyName = %S,\n", prop.name)
            add("name = %S,\n", prop.displayName)
            add("description = %S,\n", prop.description)
            add("valueType = %T.%L,\n", DocumentedValueType::class, valueType.name)
            add("isNullable = %L,\n", prop.isNullable)
            add("isSecret = %L,\n", prop.isSecret)
            if (enumValues != null) {
                add("enumValues = listOf(")
                enumValues.forEachIndexed { i, v ->
                    add(if (i > 0) ", %S" else "%S", v)
                }
                add("),\n")
            } else {
                add("enumValues = null,\n")
            }
            add("currentValue = %L,\n", valueExpr)
            unindent()
            add(")$trailingComma\n")
        }
    }

    private fun buildExtrasCodeBlock(extras: Map<String, String>): CodeBlock {
        if (extras.isEmpty()) {
            return CodeBlock.of("emptyMap()")
        }
        return buildCodeBlock {
            add("mapOf(")
            extras.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) add(", ")
                add("%S to %S", key, value)
            }
            add(")")
        }
    }

    private fun generateRegistryFile() {
        val registryPackage = "fraggle.documented.generated"

        val properties = processedClasses.map { processedClass ->
            val fieldName = processedClass.className.replaceFirstChar { it.lowercaseChar() }
            PropertySpec.builder(fieldName, ClassDocumentationInfo::class)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return %L.info", "${processedClass.className}Doc")
                        .build()
                )
                .build()
        }

        val allProperty = PropertySpec.builder(
            "all",
            List::class.asClassName().parameterizedBy(ClassDocumentationInfo::class.asClassName())
        )
            .addKdoc("All documented configuration classes.")
            .initializer(
                buildCodeBlock {
                    addStatement("listOf(")
                    indent()
                    processedClasses.forEachIndexed { index, processedClass ->
                        val comma = if (index < processedClasses.size - 1) "," else ""
                        addStatement("%L.info$comma", "${processedClass.className}Doc")
                    }
                    unindent()
                    add(")")
                }
            )
            .build()

        val topLevelConfigs = processedClasses.filter {
            it.className == "FraggleSettings" || it.className == "FraggleConfig"
        }

        val withValuesFunctions = topLevelConfigs.map { processedClass ->
            val fieldName = processedClass.className.replaceFirstChar { it.lowercaseChar() }
            val modelClassName = ClassName(processedClass.packageName, processedClass.className)

            FunSpec.builder("${fieldName}WithValues")
                .addKdoc("Get %L documentation with current values.", processedClass.className)
                .addParameter("instance", modelClassName)
                .returns(ClassDocumentationInfo::class)
                .addStatement("return %L.withValues(instance)", "${processedClass.className}Doc")
                .build()
        }

        val objectSpec = TypeSpec.objectBuilder("ConfigDocumentation")
            .addKdoc("Access point for all configuration documentation.")
            .addProperties(properties)
            .addProperty(allProperty)
            .addFunctions(withValuesFunctions)
            .build()

        val fileSpecBuilder = FileSpec.builder(registryPackage, "ConfigDocumentation")
            .addFileComment("Generated by DocumentedProcessor - do not edit")

        // Add imports for all model classes and their Doc objects
        processedClasses.forEach { processedClass ->
            fileSpecBuilder.addImport(processedClass.packageName, processedClass.className)
            fileSpecBuilder.addImport(processedClass.packageName, "${processedClass.className}Doc")
        }

        fileSpecBuilder.addType(objectSpec)

        fileSpecBuilder.build().writeTo(
            codeGenerator,
            Dependencies(true, *processedClasses.map { it.containingFile }.toTypedArray())
        )
    }

    private fun isDocumentedClass(declaration: KSClassDeclaration): Boolean {
        return declaration.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == documentedAnnotation
        }
    }

    private fun unwrapNullableType(type: KSType): KSType {
        return if (type.isMarkedNullable) {
            type.makeNotNullable()
        } else {
            type
        }
    }

    private fun determineValueType(prop: ProcessedProperty): DocumentedValueType {
        if (prop.isNestedDocumented) {
            return DocumentedValueType.NESTED_OBJECT
        }

        val unwrappedType = unwrapNullableType(prop.type)
        val typeName = unwrappedType.declaration.qualifiedName?.asString()
            ?: unwrappedType.declaration.simpleName.asString()

        return when {
            typeName == "kotlin.String" -> DocumentedValueType.STRING
            typeName == "kotlin.Int" -> DocumentedValueType.INT
            typeName == "kotlin.Long" -> DocumentedValueType.LONG
            typeName == "kotlin.Double" || typeName == "kotlin.Float" -> DocumentedValueType.DOUBLE
            typeName == "kotlin.Boolean" -> DocumentedValueType.BOOLEAN
            typeName.startsWith("kotlin.collections.List") -> DocumentedValueType.LIST
            (unwrappedType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS -> DocumentedValueType.ENUM
            else -> DocumentedValueType.UNKNOWN
        }
    }

    private fun getEnumValues(type: KSType): List<String>? {
        val unwrappedType = unwrapNullableType(type)
        val enumDecl = unwrappedType.declaration as? KSClassDeclaration ?: return null

        if (enumDecl.classKind != ClassKind.ENUM_CLASS) return null

        val entries = enumDecl.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()

        return entries.ifEmpty { null }
    }

    private data class ProcessedClass(
        val packageName: String,
        val className: String,
        val qualifiedName: String,
        val displayName: String,
        val description: String,
        val extras: Map<String, String>,
        val properties: List<ProcessedProperty>,
        val containingFile: KSFile,
    )

    private data class ProcessedProperty(
        val name: String,
        val displayName: String,
        val description: String,
        val isSecret: Boolean,
        val type: KSType,
        val isNullable: Boolean,
        val isNestedDocumented: Boolean,
        val nestedClassName: String?,
        val nestedClassQualifiedName: String?,
    )
}

/**
 * Provider for the DocumentedProcessor.
 */
class DocumentedProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return DocumentedProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            options = environment.options,
        )
    }
}
