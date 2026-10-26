package io.github.jichuo1.meguviz.ui

import android.view.View
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.state.EncodedState
import io.github.jichuo1.meguviz.ui.state.NodeIdentity
import io.github.jichuo1.meguviz.ui.state.StableIdStore
import io.github.jichuo1.meguviz.ui.state.StateCodec
import io.github.jichuo1.meguviz.ui.state.StateValue
import io.github.jichuo1.meguviz.ui.state.snapshot
import org.junit.Assert.*
import org.junit.Test

/** Pure identity/installation/codec tests. Android class literals do not execute platform behavior. */
class IdentityRegistryStateTest {
    private val type = ComponentType("view", View::class.java)
    private fun registration(
        id: String,
        priority: Int = 0,
        overrides: Set<String> = emptySet(),
        dependencies: Set<String> = emptySet(),
        capabilities: Set<String> = emptySet(),
        spiMajor: Int = 1,
        spiMinor: Int = 0,
        component: ComponentType<View> = type,
    ) = FactoryRegistration(
        ExtensionDescriptor(id, "1.0.0", spiMajor, spiMinor, capabilities, dependencies, overrides, priority),
        component, ViewFactory { error("Installation must not construct a View") },
    )

    @Test fun epochDomainsAreIndependentAndOldTokensExpire() {
        val first = EpochDomain()
        val second = EpochDomain()
        val before = first.token()
        val untouched = second.token()
        assertNotEquals(before, untouched)
        first.advance()
        assertNotEquals(before, first.token())
        assertEquals(untouched, second.token())
    }

    @Test fun epochOverflowReplacesIdentityInsteadOfRevivingEpochZero() {
        val domain = EpochDomain()
        val zero = domain.token()
        val counter = EpochDomain::class.java.getDeclaredField("epoch")
        counter.isAccessible = true
        counter.setLong(domain, Long.MAX_VALUE)
        val maximum = domain.token()
        domain.advance()
        assertNotEquals(zero, domain.token())
        assertNotEquals(maximum, domain.token())
        assertEquals(domain.token(), domain.token())
    }

    @Test fun mvxX01AmbiguousProvidersFailBeforeAnyConstruction() {
        val first = registration("one")
        val second = registration("two")
        assertThrows(IllegalArgumentException::class.java) { RegistrySnapshot.install(listOf(first, second)) }
        assertThrows(IllegalArgumentException::class.java) { RegistrySnapshot.install(listOf(second, first)) }
    }

    @Test fun explicitOverrideIsIndependentOfRegistrationOrder() {
        val base = registration("base")
        val replacement = registration("replacement", priority = 10, overrides = setOf("base"))
        listOf(listOf(base, replacement), listOf(replacement, base)).forEach { registrations ->
            val snapshot = RegistrySnapshot.install(registrations)
            assertEquals("replacement", snapshot.resolve(type).providerId)
            assertEquals(1L, snapshot.candidateChecks)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrySnapshot.install(listOf(base, registration("implicit", priority = 20)))
        }
    }

    @Test fun installationRejectsDuplicateMissingAndCyclicDependencies() {
        assertThrows(IllegalArgumentException::class.java) {
            RegistrySnapshot.install(listOf(registration("duplicate"), registration("duplicate")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrySnapshot.install(listOf(registration("orphan", dependencies = setOf("absent"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrySnapshot.install(listOf(
                registration("one", dependencies = setOf("two")),
                registration("two", dependencies = setOf("one")),
            ))
        }
    }

    @Test fun mvxV01UnsupportedSpiAndMissingCapabilityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RegistrySnapshot.install(listOf(registration("major", spiMajor = 2))) }
        assertThrows(IllegalArgumentException::class.java) { RegistrySnapshot.install(listOf(registration("minor", spiMinor = 1))) }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrySnapshot.install(listOf(registration("capability", capabilities = setOf("theme"))))
        }
        val snapshot = RegistrySnapshot.install(listOf(registration("capability", capabilities = setOf("theme"))), setOf("theme"))
        assertEquals("capability", snapshot.resolve(type).providerId)
    }

    @Test fun resolveRejectsSameNameWithDifferentRuntimeTypeOrSchema() {
        val snapshot = RegistrySnapshot.install(listOf(registration("base")))
        assertThrows(IllegalArgumentException::class.java) { snapshot.resolve(ComponentType("view", TextView::class.java)) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.resolve(ComponentType("view", View::class.java, 2)) }
        assertThrows(IllegalArgumentException::class.java) { snapshot.resolve(ComponentType("missing", View::class.java)) }
    }

    @Test fun snapshotDefensivelyCopiesInstallationMetadata() {
        val dependencies = mutableSetOf<String>()
        val registrations = mutableListOf(registration("base", dependencies = dependencies))
        val snapshot = RegistrySnapshot.install(registrations)
        dependencies += "absent"
        registrations.clear()
        assertEquals("base", snapshot.resolve(type).providerId)
    }

    @Test fun nativeIdTableRoundTripsAndSkipsOccupiedIdsWithoutHashingKeys() {
        val store = StableIdStore()
        val first = NodeIdentity("root/FB", "text", 1)
        val second = NodeIdentity("root/Ea", "text", 1)
        assertEquals(first.path.hashCode(), second.path.hashCode())
        val firstId = store.idFor(first, occupied = setOf(1, 2))
        val secondId = store.idFor(second, occupied = setOf(1, 2))
        assertEquals(3, firstId)
        assertEquals(4, secondId)
        val restored = StableIdStore(store.snapshot())
        assertEquals(firstId, restored.idFor(first))
        assertEquals(secondId, restored.idFor(second))
        assertThrows(IllegalArgumentException::class.java) { restored.idFor(first, occupied = setOf(firstId)) }
    }

    @Test fun nativeIdTableRejectsDuplicateIdsPathsAndIncompatibleComponent() {
        val first = NodeIdentity("root/a", "text", 1)
        val second = NodeIdentity("root/b", "text", 1)
        assertThrows(IllegalArgumentException::class.java) { StableIdStore(mapOf(first to 7, second to 7)) }
        assertThrows(IllegalArgumentException::class.java) { StableIdStore(mapOf(first to 0)) }
        assertThrows(IllegalArgumentException::class.java) { StableIdStore(mapOf(first to 0x7f010001)) }
        assertThrows(IllegalArgumentException::class.java) {
            StableIdStore(mapOf(first to 1, first.copy(component = "button") to 2))
        }
        val store = StableIdStore(mapOf(first to 7))
        val changed = first.copy(schema = 2)
        assertThrows(IllegalArgumentException::class.java) { store.idFor(changed) }
        store.rebuild(first.path)
        assertTrue(store.idFor(changed) > 0)
    }

    @Test fun stateContainersCopyMutableInputsAndCannotBeMutatedThroughJavaViews() {
        val list = mutableListOf<StateValue>(StateValue.Text("initial"))
        val map = mutableMapOf<String, StateValue>("title" to StateValue.Sequence(list))
        val state = StateValue.Record(map)
        list.clear()
        map.clear()
        assertEquals(1, (state.values.getValue("title") as StateValue.Sequence).values.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (state.values as MutableMap<String, StateValue>)["bad"] = StateValue.Empty
        }
    }

    @Test fun codecEnvelopeChecksIdentityAndPreservesExplicitVersionMigration() {
        val codec = object : StateCodec<String> {
            override val id = "title"
            override val version = 2
            override fun encode(state: String) = StateValue.Record(mapOf("title" to StateValue.Text(state)))
            override fun decode(version: Int, data: StateValue.Record): String {
                val key = when (version) { 1 -> "text"; 2 -> "title"; else -> error("Unsupported codec version") }
                return (data.values.getValue(key) as StateValue.Text).value
            }
        }
        assertEquals("current", codec.snapshot("current").decode(codec))
        assertEquals("legacy", EncodedState("title", 1, StateValue.Record(mapOf("text" to StateValue.Text("legacy")))).decode(codec))
        assertThrows(IllegalArgumentException::class.java) { EncodedState("wrong", 2, StateValue.Record(emptyMap())).decode(codec) }
        assertThrows(IllegalStateException::class.java) { EncodedState("title", 3, StateValue.Record(emptyMap())).decode(codec) }
        assertThrows(IllegalArgumentException::class.java) { EncodedState("title", 0, StateValue.Record(emptyMap())) }
    }
}
