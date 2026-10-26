package io.github.jichuo1.meguviz.ui

import android.view.View
import io.github.jichuo1.meguviz.ui.scheduler.ScheduledOperation
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import io.github.jichuo1.meguviz.ui.scheduler.StateSlot

@JvmInline
value class EffectMask(val bits: Int) {
    infix fun or(other: EffectMask) = EffectMask(bits or other.bits)
    companion object {
        val PAINT = EffectMask(1)
        val LAYOUT = EffectMask(2)
        val STRUCTURE = EffectMask(4)
        val SEMANTICS = EffectMask(8)
        val CONSERVATIVE = EffectMask(15)
    }
}

enum class PropertyOwnership { EXCLUSIVE, SHARED_READ, ALWAYS_WRITE }

/** Equality is opt-in. snapshot must detach mutable input; effects never suppress native setters. */
class PropertySpec<V : View, T>(
    val id: String,
    val effect: EffectMask = EffectMask.CONSERVATIVE,
    val ownership: PropertyOwnership = PropertyOwnership.ALWAYS_WRITE,
    val coalescible: Boolean = false,
    val equivalent: ((T, T) -> Boolean)? = null,
    val snapshot: (T) -> T = { it },
    val read: ((V) -> T)? = null,
    val comparisonAllowed: (V) -> Boolean = { true },
    val resourceSensitive: Boolean = false,
    val write: (V, T) -> Unit,
) {
    init {
        require(id.isNotBlank())
        require(ownership != PropertyOwnership.SHARED_READ || read != null) { "Shared comparison requires a live getter" }
    }
}

class PropertyBinding<V : View, T> internal constructor(
    private val ref: ViewRef<V>,
    spec: PropertySpec<V, T>,
    release: () -> Unit,
) : AutoCloseable {
    private class Applied<T>(val value: T)
    private var applied: Applied<T>? = null
    private var closed = false
    private var property: PropertySpec<V, T>? = spec
    private var releaseAction: (() -> Unit)? = release
    private val slot = StateSlot(ref.identity, spec.id)
    var writes: Long = 0; private set
    var skips: Long = 0; private set

    fun invalidate() { ref.session.assertOwner(); applied = null }

    fun submit(value: T): SubmitResult {
        ref.session.assertOwner()
        check(!closed) { "Binding closed" }
        val spec = checkNotNull(property)
        val snapshot = spec.snapshot(value)
        val token = ref.token(resources = spec.resourceSensitive)
        val valid = { !closed && token.isCurrent() }
        val action = { apply(snapshot) }
        return if (spec.coalescible) ref.session.scheduler.submitState(slot, valid, action)
        else ref.session.scheduler.submitCommand(valid, action)
    }

    /** Used by ordered batches; the batch preserves calls and is not a transaction. */
    fun operation(value: T): ScheduledOperation {
        ref.session.assertOwner()
        check(!closed)
        val spec = checkNotNull(property)
        val snapshot = spec.snapshot(value)
        val token = ref.token(resources = spec.resourceSensitive)
        return ScheduledOperation(isValid = { !closed && token.isCurrent() }, action = { apply(snapshot) })
    }

    private fun apply(value: T) {
        val spec = checkNotNull(property)
        val view = ref.get()
        val equals = if (spec.comparisonAllowed(view)) spec.equivalent else null
        if (closed) return
        val previous = when (spec.ownership) {
            PropertyOwnership.EXCLUSIVE -> applied
            PropertyOwnership.SHARED_READ -> Applied(spec.read!!.invoke(view))
            PropertyOwnership.ALWAYS_WRITE -> null
        }
        if (closed) return
        if (equals != null && previous != null && equals(previous.value, value)) { skips++; return }
        if (closed) return
        spec.write(view, value)
        writes++
        if (closed) return
        // A rejecting/rolling-back listener owns the final value when a getter is supplied.
        val confirmed = spec.snapshot(spec.read?.invoke(view) ?: value)
        if (!closed) applied = Applied(confirmed)
    }

    override fun close() {
        ref.session.assertOwner()
        if (closed) return
        closed = true
        applied = null
        property = null
        val release = releaseAction
        releaseAction = null
        try { release?.invoke() } finally { ref.session.scheduler.discardInvalid() }
    }
}
