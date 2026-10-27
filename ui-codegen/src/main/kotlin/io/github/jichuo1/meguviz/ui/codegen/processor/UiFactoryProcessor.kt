package io.github.jichuo1.meguviz.ui.codegen.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

private const val ANNOTATION = "io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory"
private val KOTLIN_KEYWORDS = setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while")
private fun validIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value !in KOTLIN_KEYWORDS

class UiFactoryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        UiFactoryProcessor(environment.codeGenerator, environment.logger)
}

private class UiFactoryProcessor(private val output: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {
    private val generated = mutableSetOf<String>()
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        val view = resolver.getClassDeclarationByName(resolver.getKSNameFromString("android.view.View"))?.asStarProjectedType()
        for (symbol in resolver.getSymbolsWithAnnotation(ANNOTATION)) {
            if (!symbol.validate(enableNewFeatures = true)) { deferred += symbol; continue }
            val type = symbol as? KSClassDeclaration
            if (type == null) { logger.error("GenerateUiFactory requires a class", symbol); continue }
            val source = type.containingFile
            val name = type.qualifiedName?.asString()
            val constructor = type.primaryConstructor
            val invalidVisibility = setOf(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL)
            val valid = source != null && name != null && type.parentDeclaration == null &&
                type.classKind == ClassKind.CLASS && type.typeParameters.isEmpty() &&
                type.modifiers.none { it in invalidVisibility || it == Modifier.ABSTRACT || it == Modifier.INNER || it == Modifier.SEALED } &&
                constructor != null && constructor.modifiers.none { it in invalidVisibility } &&
                constructor.parameters.size == 1 &&
                constructor.parameters.single().type.resolve().declaration.qualifiedName?.asString() == "android.content.Context" &&
                constructor.parameters.single().type.resolve().nullability == com.google.devtools.ksp.symbol.Nullability.NOT_NULL &&
                view != null && view.isAssignableFrom(type.asStarProjectedType())
            if (!valid) {
                logger.error("GenerateUiFactory requires a public top-level concrete non-generic View with a public single non-null Context primary constructor", type)
                continue
            }
            val annotation = type.annotations.first { it.annotationType.resolve().declaration.qualifiedName?.asString() == ANNOTATION }
            val id = annotation.arguments.firstOrNull { it.name?.asString() == "id" }?.value as? String
            val schema = annotation.arguments.firstOrNull { it.name?.asString() == "schema" }?.value as? Int ?: 1
            if (id == null || !id.matches(Regex("[A-Za-z0-9_.:-]+")) || schema <= 0) {
                logger.error("GenerateUiFactory requires a nonempty stable id containing letters, digits, dot, colon, underscore or dash and a positive schema", type)
                continue
            }
            if (!generated.add(name)) continue
            val packageName = type.packageName.asString()
            val simple = type.simpleName.asString()
            if (!validIdentifier(simple) || packageName.isBlank() || packageName.split('.').any { !validIdentifier(it) }) {
                logger.error("GenerateUiFactory requires a conventional class name in a named package", type)
                continue
            }
            output.createNewFile(Dependencies(false, source), packageName, "${simple}UiGenerated").bufferedWriter().use { writer ->
                writer.write("""package $packageName

import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration

typealias ${simple}ViewRef = ViewRef<$simple>

object ${simple}UiGenerated {
    const val ABI_MAJOR: Int = 1
    val type = ComponentType("$id", $simple::class.java, $schema)
    val factory = ViewFactory<$simple> { environment -> $simple(environment.context) }
    fun registration(): FactoryRegistration<$simple> = FactoryRegistration(
        ExtensionDescriptor("generated:$id", "1.0.0"), type, factory
    )
}
""")
            }
        }
        return deferred
    }
}
