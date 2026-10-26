package io.github.jichuo1.meguviz.ui.state

/** Closed, immutable pure-data values. No Parcelable, Context, lambda, receiver or epoch. */
sealed interface StateValue {
    data class Text(val value: String) : StateValue
    data class Integer(val value: Long) : StateValue
    data class Decimal(val value: Double) : StateValue
    data class Flag(val value: Boolean) : StateValue
    data object Empty : StateValue
    class Sequence(values: List<StateValue>) : StateValue { val values: List<StateValue> = java.util.Collections.unmodifiableList(values.toList()) }
    class Record(values: Map<String, StateValue>) : StateValue { val values: Map<String, StateValue> = java.util.Collections.unmodifiableMap(values.toMap()) }
}

interface StateCodec<S> {
    val id: String
    val version: Int
    fun encode(state: S): StateValue.Record
    /** Implement explicit migrations here; unknown versions must be rejected. */
    fun decode(version: Int, data: StateValue.Record): S
}

data class EncodedState(val codecId: String, val version: Int, val data: StateValue.Record) {
    init { require(codecId.isNotBlank()); require(version > 0) }
    fun <S> decode(codec: StateCodec<S>): S {
        require(codec.id == codecId) { "Wrong state codec" }
        return codec.decode(version, data)
    }
}

fun <S> StateCodec<S>.snapshot(state: S): EncodedState = EncodedState(id, version, encode(state))

data class NodeIdentity(val path: String, val component: String, val schema: Int) {
    init { require(path.isNotBlank()); require(component.isNotBlank()); require(schema > 0) }
}

/** Persist the table. Sequential IDs are checked against both the table and the native tree. */
class StableIdStore(restored: Map<NodeIdentity, Int> = emptyMap()) {
    private val ids = restored.toMutableMap()
    private var nextId = 1
    init {
        require(ids.values.all { it in 1..0x00ffffff }) { "Stable IDs must be in the generated-ID range" }
        require(ids.values.distinct().size == ids.size) { "Duplicate native ID" }
        require(ids.keys.map { it.path }.distinct().size == ids.size) { "Duplicate stable path" }
    }

    fun idFor(identity: NodeIdentity, occupied: Set<Int> = emptySet()): Int {
        val oldIdentity = ids.keys.firstOrNull { it.path == identity.path }
        require(oldIdentity == null || oldIdentity == identity) { "Component type/schema changed; explicitly rebuild its saved identity" }
        ids[identity]?.let { require(it !in occupied) { "Restored ID conflicts with native View" }; return it }
        val used = ids.values.toHashSet()
        while (nextId in used || nextId in occupied) {
            check(nextId < 0x00ffffff) { "Stable View IDs exhausted" }
            nextId++
        }
        check(nextId <= 0x00ffffff)
        return nextId.also { ids[identity] = it; nextId++ }
    }

    fun rebuild(path: String) { ids.keys.removeAll { it.path == path } }
    fun snapshot(): Map<NodeIdentity, Int> = ids.toMap()
}
