package io.github.jichuo1.meguviz.ui.ref

import android.content.Context
import android.view.View
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec
import io.github.jichuo1.meguviz.ref.runtime.invoke.ConstructorPlan
import io.github.jichuo1.meguviz.ref.runtime.invoke.MethodPlan
import io.github.jichuo1.meguviz.ref.runtime.invoke.StalePlanException
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.SessionState
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.scheduler.ScheduledOperation

/** UI-owned lifetime. It borrows the real CodeScope and never closes that shared code owner. */
class RefBindingSession internal constructor(
    scope: CodeScope,
    ownerCheck: () -> Unit,
    uiCurrent: () -> Boolean = { true },
    invalidated: () -> Unit = {},
) : AutoCloseable {
    private var codeScope: CodeScope? = scope
    private var checkOwner: (() -> Unit)? = ownerCheck
    private var currentUi: (() -> Boolean)? = uiCurrent
    private var onInvalidated: (() -> Unit)? = invalidated
    private var ui: UiSession? = null
    private val identity = scope.identity()
    private val epoch = scope.codeEpoch()
    private val owned = linkedSetOf<AutoCloseable>()
    val isClosed: Boolean get() = codeScope == null
    internal val retainedHandleCount: Int get() = owned.size

    init { ownerCheck(); require(scope.state() == CodeScope.State.OPEN) { "CodeScope must be open" } }

    companion object {
        fun attach(ui: UiSession, code: CodeScope): RefBindingSession {
            ui.assertOwner()
            val binding = RefBindingSession(code, ui::assertOwner,
                { ui.state == SessionState.ACTIVE }, { ui.scheduler.discardInvalid() })
            binding.ui = ui
            try { ui.onClose(binding::close) } catch (failure: Throwable) { binding.close(); throw failure }
            return binding
        }
    }

    /** Logical validity is rechecked on every call; no invented upstream close event or lease API. */
    fun isCurrent(): Boolean {
        val scope = codeScope ?: return false
        checkOwner?.invoke()
        return currentUi?.invoke() == true && codeScope === scope && scope.state() == CodeScope.State.OPEN &&
            scope.identity() == identity && scope.codeEpoch() == epoch
    }

    internal fun activeScope(): CodeScope {
        checkOwner?.invoke()
        val scope = checkNotNull(codeScope) { "Ref binding session is closed" }
        check(currentUi?.invoke() == true && codeScope === scope) { "UI session is not active" }
        if (scope.state() != CodeScope.State.OPEN || scope.identity() != identity || scope.codeEpoch() != epoch) {
            throw StalePlanException("The borrowed CodeScope identity or epoch changed")
        }
        return scope
    }

    /** Resolution happens once. Construction keeps the caller's actual themed Context. */
    fun <V : View> viewFactory(
        type: Class<V>,
        parameterTypes: Array<Class<*>> = arrayOf(Context::class.java),
        arguments: (CreationEnvironment) -> Array<out Any?> = { arrayOf(it.context) },
    ): RefViewFactory<V> {
        val scope = activeScope()
        require(scope.usesLoader(type.classLoader)) { "Dynamic factory type must belong to this CodeScope's actual loader" }
        val query = QuerySpec.builder(type, MemberKind.CONSTRUCTOR).parameters(*parameterTypes.copyOf()).build()
        val plan = scope.bindConstructor(scope.resolve(query).requireSingle())
        activeScope()
        return RefViewFactory(this, type, plan, arguments).also(owned::add)
    }

    /** onRelease verifies the ref belongs to this UiSession before a receiver lease is exposed. */
    fun <V : View> bindView(ref: ViewRef<V>, resources: Boolean = false): RefViewLease<V> {
        activeScope()
        val token = ref.token(resources)
        val receiver = receiver(ref.get(), token::isCurrent)
        val lease = RefViewLease(receiver)
        try { checkNotNull(ui) { "Use attach for View bindings" }.onRelease(ref, lease::close) }
        catch (failure: Throwable) { receiver.close(); throw failure }
        return lease
    }

    internal fun <T : Any> receiver(value: T, valid: () -> Boolean = { true }): ReceiverLease<T> {
        activeScope()
        return ReceiverLease(this, value, valid).also(owned::add)
    }

    internal fun released(value: AutoCloseable) { owned.remove(value); onInvalidated?.invoke() }
    internal fun assertOwner() { checkOwner?.invoke() }
    internal fun invalidatePending() { checkOwner?.invoke(); onInvalidated?.invoke() }

    override fun close() {
        if (isClosed) return
        checkOwner?.invoke()
        codeScope = null
        val callbacks = owned.toList(); owned.clear()
        val invalidate = onInvalidated
        onInvalidated = null
        var first: Throwable? = null
        callbacks.forEach { try { it.close() } catch (failure: Throwable) {
            if (first == null) first = failure else first.addSuppressed(failure)
        } }
        currentUi = null; ui = null; checkOwner = null
        try { invalidate?.invoke() } catch (failure: Throwable) {
            if (first == null) first = failure else first.addSuppressed(failure)
        }
        first?.let { throw it }
    }
}

/** Resolved constructor handle. Failure is propagated without selecting another constructor/provider. */
class RefViewFactory<V : View> internal constructor(
    owner: RefBindingSession, type: Class<V>, plan: ConstructorPlan,
    arguments: (CreationEnvironment) -> Array<out Any?>,
) : ViewFactory<V>, AutoCloseable {
    private var owner: RefBindingSession? = owner
    private var type: Class<V>? = type
    private var plan: ConstructorPlan? = plan
    private var arguments: ((CreationEnvironment) -> Array<out Any?>)? = arguments

    override fun create(environment: CreationEnvironment): V {
        val owner = checkNotNull(owner) { "Dynamic factory is closed" }
        owner.activeScope()
        val values = checkNotNull(arguments).invoke(environment)
        owner.activeScope()
        val instance = checkNotNull(plan).newInstance(values)
        owner.activeScope()
        return checkNotNull(checkNotNull(type).cast(instance))
    }

    override fun close() {
        val previous = owner ?: return
        previous.assertOwner()
        owner = null; type = null; plan = null; arguments = null
        previous.released(this)
    }
}

/** UI-owned receiver lease, distinct from the upstream's receiver-free plans. */
class RefViewLease<V : View> internal constructor(private val receiver: ReceiverLease<V>) : AutoCloseable {
    fun isCurrent(): Boolean = receiver.isCurrent()
    fun method(query: QuerySpec): RefMethodHandle = receiver.method(query)
    fun operation(label: String = "ref-view", action: (V) -> Unit): ScheduledOperation =
        ScheduledOperation(label, receiver::isCurrent) { action(receiver.value()) }
    override fun close() = receiver.close()
}

internal class ReceiverLease<T : Any>(
    owner: RefBindingSession, receiver: T, valid: () -> Boolean,
) : AutoCloseable {
    private var owner: RefBindingSession? = owner
    private var receiver: T? = receiver
    private var valid: (() -> Boolean)? = valid
    private val methods = linkedSetOf<RefMethodHandle>()
    internal val retainsReceiver: Boolean get() = receiver != null

    fun isCurrent(): Boolean {
        val current = owner ?: return false
        if (receiver == null || !current.isCurrent() || valid?.invoke() != true) return false
        return owner === current && receiver != null && current.isCurrent()
    }
    fun assertOwner() { owner?.assertOwner() }

    fun value(): T {
        val owner = checkNotNull(owner) { "Receiver lease is closed" }
        owner.activeScope()
        check(valid?.invoke() == true) { "View binding token is stale" }
        owner.activeScope()
        return checkNotNull(receiver) { "Receiver lease is closed" }
    }

    fun method(query: QuerySpec): RefMethodHandle {
        value()
        val scope = checkNotNull(owner).activeScope()
        val plan = scope.bindMethod(scope.resolve(query).requireSingle())
        value()
        return RefMethodHandle(this, plan).also(methods::add)
    }

    fun released(method: RefMethodHandle) { methods.remove(method); owner?.invalidatePending() }

    override fun close() {
        val previous = owner ?: return
        previous.assertOwner()
        owner = null; receiver = null; valid = null
        val pending = methods.toList(); methods.clear()
        pending.forEach(RefMethodHandle::close)
        previous.released(this)
    }
}

/** A bound real MethodPlan. Its errors, including InvocationTargetException, remain unchanged. */
class RefMethodHandle internal constructor(receiver: ReceiverLease<*>, plan: MethodPlan) : AutoCloseable {
    private var receiver: ReceiverLease<*>? = receiver
    private var plan: MethodPlan? = plan
    fun isCurrent(): Boolean = receiver?.isCurrent() == true

    fun invoke(vararg arguments: Any?): Any? {
        val value = checkNotNull(receiver) { "Method handle is closed" }.value()
        return checkNotNull(plan).invoke(value, arguments)
    }

    /** Submit through the owning UiSession scheduler; this remains an ordered, unmerged command. */
    fun operation(label: String = "ref-method", vararg arguments: Any?): ScheduledOperation {
        val values = arguments.copyOf()
        return ScheduledOperation(label, ::isCurrent) { invoke(*values) }
    }

    override fun close() {
        val previous = receiver ?: return
        previous.assertOwner()
        receiver = null; plan = null
        previous.released(this)
    }
}
