package io.github.jichuo1.meguviz.ui.extensions

import android.view.View
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.ViewFactory

data class ExtensionDescriptor(
    val id: String,
    val version: String,
    val spiMajor: Int = 1,
    val spiMinor: Int = 0,
    val requiredCapabilities: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val overrides: Set<String> = emptySet(),
    val priority: Int = 0,
    val thread: String = "main",
    val lifetime: String = "session",
) {
    init { require(id.isNotBlank()); require(version.isNotBlank()); require(spiMinor >= 0) }
}

class FactoryRegistration<V : View>(val descriptor: ExtensionDescriptor, val type: ComponentType<V>, val factory: ViewFactory<V>)

/** Own this snapshot in a session, never in a process-global registry. */
class RegistrySnapshot private constructor(private val entries: Map<String, FactoryRegistration<*>>) {
    var candidateChecks: Long = 0; private set

    fun <V : View> resolve(type: ComponentType<V>): ResolvedBinding<V> {
        candidateChecks++
        val entry = requireNotNull(entries[type.id]) { "No provider for ${type.id}" }
        require(entry.type.schema == type.schema && entry.type.viewClass === type.viewClass) { "Component schema/type mismatch" }
        return ResolvedBinding(type, entry.descriptor.id, ViewFactory { env -> type.viewClass.cast(entry.factory.create(env)) })
    }

    companion object {
        fun install(registrations: List<FactoryRegistration<*>>, capabilities: Set<String> = emptySet()): RegistrySnapshot {
            val frozen = registrations.map { entry ->
                FactoryRegistration(entry.descriptor.copy(
                    requiredCapabilities = entry.descriptor.requiredCapabilities.toSet(),
                    dependencies = entry.descriptor.dependencies.toSet(), overrides = entry.descriptor.overrides.toSet(),
                ), entry.type, entry.factory)
            }
            val ids = frozen.map { it.descriptor.id }
            require(ids.distinct().size == ids.size) { "Duplicate provider id" }
            val byId = frozen.associateBy { it.descriptor.id }
            val done = mutableSetOf<String>()
            val visiting = mutableSetOf<String>()
            fun visit(id: String) {
                if (id in done) return
                require(visiting.add(id)) { "Extension dependency cycle" }
                val descriptor = requireNotNull(byId[id]) { "Missing dependency: $id" }.descriptor
                require(descriptor.spiMajor == 1 && descriptor.spiMinor == 0) { "Unsupported SPI version" }
                require(descriptor.thread == "main" && descriptor.lifetime == "session") { "Unsupported lifecycle/thread contract" }
                require(capabilities.containsAll(descriptor.requiredCapabilities)) { "Missing capabilities for $id" }
                require(descriptor.overrides.all { it in byId && it != id }) { "Invalid override" }
                descriptor.dependencies.forEach(::visit)
                visiting.remove(id); done.add(id)
            }
            ids.forEach(::visit)
            val selected = frozen.groupBy { it.type.id }.mapValues { (_, group) ->
                if (group.size == 1) group.single()
                else {
                    val winner = group.maxBy { it.descriptor.priority }
                    require(group.count { it.descriptor.priority == winner.descriptor.priority } == 1) { "Ambiguous provider priority" }
                    require(winner.descriptor.overrides.containsAll(group.filter { it !== winner }.map { it.descriptor.id })) { "Provider conflict requires explicit override" }
                    require(group.all { it.type.schema == winner.type.schema && it.type.viewClass === winner.type.viewClass }) { "Override changes component contract" }
                    winner
                }
            }
            return RegistrySnapshot(selected.toMap())
        }
    }
}

/** Already resolved. Construction errors propagate once; there is no provider fallback. */
class ResolvedBinding<V : View> internal constructor(val type: ComponentType<V>, val providerId: String, private val factory: ViewFactory<V>) : ViewFactory<V> {
    override fun create(environment: CreationEnvironment): V = factory.create(environment)
}
