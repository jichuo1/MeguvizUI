package io.github.jichuo1.meguviz.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.jichuo1.meguviz.ui.scheduler.ChoreographerFrameDriver
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import io.github.jichuo1.meguviz.ui.scheduler.SchedulerState
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class SessionState { ACTIVE, DETACHED, DISPOSED }

class CloseRequest internal constructor() {
    @Volatile var completed: Boolean = false; internal set
    @Volatile var failure: Throwable? = null; internal set
}

/** One owner thread, one immutable frame driver, no global View or Context registry. */
class UiSession(
    environment: CreationEnvironment,
    driver: UiFrameDriver = ChoreographerFrameDriver(),
) : AutoCloseable {
    private var environmentValue: CreationEnvironment? = environment
    val environment: CreationEnvironment get() { assertOwner(); return checkNotNull(environmentValue) { "Session disposed" } }
    private val handler = Handler(Looper.getMainLooper())
    private val closeRequested = AtomicBoolean(false)
    private val closeReceipt = CloseRequest()
    private val attachDomain = EpochDomain()
    private val themeDomain = EpochDomain()
    private val resourceDomain = EpochDomain()
    private var nextIdentity = 1L
    private val nodes = linkedMapOf<Long, Node>()
    private val parentKeys = IdentityHashMap<ViewGroup, MutableSet<ViewKey>>()
    private val views = IdentityHashMap<View, Long>()
    private val sessionCleanup = mutableListOf<() -> Unit>()
    private class Node(
        val view: View,
        val key: ViewKey,
        val component: ComponentType<*>,
        val parent: ViewGroup?,
        val ownership: NativeOwnership,
        val binding: EpochDomain = EpochDomain(),
        val cleanup: MutableList<() -> Unit> = mutableListOf(),
        val properties: MutableSet<String> = mutableSetOf(),
    )
    var state = SessionState.ACTIVE; private set
    val scheduler = UiScheduler(driver, ::assertOwner)

    init { assertOwner() }

    fun assertOwner() { check(Looper.myLooper() === Looper.getMainLooper()) { "UI operation requires the main thread" } }
    private fun requireActive() { assertOwner(); check(state == SessionState.ACTIVE && !closeRequested.get() && scheduler.state == SchedulerState.ACTIVE) { "Session is not active" } }

    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> mount(
        parent: P, policy: ParentPolicy<P, LP>, key: ViewKey, component: ComponentType<V>,
        params: LP, factory: ViewFactory<V>, configure: V.() -> Unit = {},
    ): ViewRef<V> {
        requireActive()
        reserve(parent, key)
        var view: V? = null
        var acquired = false
        try {
            view = factory.create(environment)
            require(component.viewClass.isInstance(view)) { "Factory type differs from component" }
            check(view.parent == null) { "Factory returned an already parented View" }
            check(!views.containsKey(view)) { "View already managed" }
            acquired = true
            view.configure()
            requireActive()
            policy.attach(parent, view, params)
            return register(view, parent, key, component, NativeOwnership.CREATED)
        } catch (failure: Throwable) {
            if (acquired && view != null && view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    fun <V : View> borrow(view: V, key: ViewKey, component: ComponentType<V>): ViewRef<V> {
        requireActive()
        check(!views.containsKey(view)) { "View already managed" }
        require(component.viewClass.isInstance(view))
        val parent = view.parent as? ViewGroup
        if (parent != null) reserve(parent, key)
        else check(nodes.values.none { it.parent == null && it.key == key }) { "Duplicate root key: $key" }
        return register(view, parent, key, component, NativeOwnership.BORROWED)
    }

    fun <P : ViewGroup, LP : ViewGroup.LayoutParams, V : View> transfer(
        view: V, parent: P, policy: ParentPolicy<P, LP>, params: LP, key: ViewKey, component: ComponentType<V>,
    ): ViewRef<V> {
        requireActive()
        require(view.parent == null) { "Explicitly detach before transferring; UI never steals an external child" }
        require(component.viewClass.isInstance(view))
        check(!views.containsKey(view))
        reserve(parent, key)
        try {
            policy.attach(parent, view, params)
            return register(view, parent, key, component, NativeOwnership.TRANSFERRED)
        } catch (failure: Throwable) {
            if (view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    /** Uses the caller's inflater/Factory2 and real parent. Managed merge roots are rejected. */
    fun inflate(inflater: LayoutInflater, resource: Int, parent: ViewGroup, key: ViewKey): ViewRef<View> {
        requireActive()
        reserve(parent, key)
        var view: View? = null
        var acquired = false
        try {
            inflater.context.resources.getLayout(resource).use { parser ->
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG && parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) parser.next()
                require(parser.name != "merge") { "Managed <merge> requires an explicit ownership protocol" }
            }
            view = inflater.inflate(resource, parent, false)
            check(view.parent == null && !views.containsKey(view)) { "Inflater returned an already owned View" }
            acquired = true
            requireActive()
            parent.addView(view)
            return register(view, parent, key, ComponentType("xml:$resource", View::class.java), NativeOwnership.CREATED)
        } catch (failure: Throwable) {
            if (acquired && view != null && view.parent === parent) parent.removeView(view)
            unreserve(parent, key)
            throw failure
        }
    }

    private fun reserve(parent: ViewGroup, key: ViewKey) {
        check(parentKeys.getOrPut(parent) { mutableSetOf() }.add(key)) { "Duplicate key in parent: ${key.value}" }
    }
    private fun unreserve(parent: ViewGroup, key: ViewKey) { parentKeys[parent]?.let { it.remove(key); if (it.isEmpty()) parentKeys.remove(parent) } }
    private fun <V : View> register(view: V, parent: ViewGroup?, key: ViewKey, component: ComponentType<V>, ownership: NativeOwnership): ViewRef<V> {
        requireActive()
        check(nextIdentity != Long.MAX_VALUE) { "Session node identities exhausted; create a new session" }
        val identity = nextIdentity++
        nodes[identity] = Node(view, key, component, parent, ownership)
        views[view] = identity
        return ViewRef(this, identity, key, component)
    }
    internal fun <V : View> resolve(ref: ViewRef<V>): V {
        assertOwner(); check(ref.session === this); check(state != SessionState.DISPOSED)
        val node = checkNotNull(nodes[ref.identity]) { "View reference released" }
        return requireNotNull(ref.component.viewClass.cast(node.view))
    }

    fun <V : View> token(ref: ViewRef<V>, resources: Boolean = false): BindingToken {
        resolve(ref)
        val binding = nodes.getValue(ref.identity).binding.token()
        val attach = attachDomain.token()
        val theme = themeDomain.token()
        val localResource = resourceDomain.token()
        val upstream = if (resources) environment.resourceAuthority?.capture?.invoke() else null
        return BindingToken {
            assertOwner()
            state == SessionState.ACTIVE && !closeRequested.get() &&
                nodes[ref.identity]?.binding?.token() == binding && attachDomain.token() == attach &&
                (!resources || (themeDomain.token() == theme &&
                    if (upstream != null) environmentValue?.resourceAuthority?.isCurrent?.invoke(upstream) == true else resourceDomain.token() == localResource))
        }
    }

    fun <V : View, T> bind(ref: ViewRef<V>, property: PropertySpec<V, T>): PropertyBinding<V, T> {
        requireActive(); resolve(ref)
        val node = nodes.getValue(ref.identity)
        check(node.properties.add(property.id)) { "Property already owned: ${property.id}" }
        val binding = PropertyBinding(ref, property) { nodes[ref.identity]?.properties?.remove(property.id) }
        node.cleanup += { binding.close() }
        return binding
    }

    /** Reserve properties controlled by Lumen/animations before installing UI bindings. */
    fun <V : View> reserveProperties(ref: ViewRef<V>, ids: Set<String>): AutoCloseable {
        requireActive(); resolve(ref)
        val node = nodes.getValue(ref.identity)
        check(ids.none { it in node.properties }) { "Conflicting visual property owner" }
        node.properties.addAll(ids)
        var closed = false
        val release = { assertOwner(); if (!closed) { closed = true; nodes[ref.identity]?.properties?.removeAll(ids) }; Unit }
        node.cleanup += release
        return AutoCloseable(release)
    }

    fun <V : View> onRelease(ref: ViewRef<V>, cleanup: () -> Unit) { requireActive(); resolve(ref); nodes.getValue(ref.identity).cleanup += cleanup }
    fun onClose(cleanup: () -> Unit) { requireActive(); sessionCleanup += cleanup }

    /** Caller explicitly grants this listener slot. Externally replaced listeners must revoke first. */
    fun <V : View> click(ref: ViewRef<V>, action: (V) -> Unit): AutoCloseable {
        val ownership = reserveProperties(ref, setOf("event:click"))
        var view: V? = ref.get()
        val binding = nodes.getValue(ref.identity).binding.token()
        view!!.setOnClickListener {
            if (state == SessionState.ACTIVE && !closeRequested.get() && nodes[ref.identity]?.binding?.token() == binding) action(ref.get())
        }
        var closed = false
        val release = { assertOwner(); if (!closed) { closed = true; val target = view; view = null; target?.setOnClickListener(null); ownership.close() }; Unit }
        onRelease(ref, release)
        return AutoCloseable(release)
    }

    fun rebind(ref: ViewRef<*>) {
        requireActive(); resolve(ref)
        val node = nodes.getValue(ref.identity)
        node.binding.advance()
        try { runCleanup(node.cleanup) } finally { node.properties.clear(); scheduler.discardInvalid() }
    }

    fun detach() {
        assertOwner(); check(state != SessionState.DISPOSED)
        if (state == SessionState.DETACHED) return
        state = SessionState.DETACHED; attachDomain.advance(); scheduler.pause()
    }
    fun reattach() { assertOwner(); check(state == SessionState.DETACHED); attachDomain.advance(); state = SessionState.ACTIVE; scheduler.resume() }
    fun invalidateTheme() { requireActive(); themeDomain.advance() }
    fun invalidateResources() { requireActive(); check(environment.resourceAuthority == null) { "Invalidate the upstream resource authority" }; resourceDomain.advance() }

    fun release(ref: ViewRef<*>) {
        assertOwner(); check(ref.session === this)
        val node = nodes[ref.identity] ?: return
        val children = nodes.filterValues { child -> child.parent === node.view }.keys.toList()
        val failures = mutableListOf<Throwable>()
        children.forEach { id -> nodes[id]?.let { child -> try { release(ViewRef(this, id, child.key, child.component)) } catch (t: Throwable) { failures += t } } }
        nodes.remove(ref.identity)
        node.binding.advance()
        scheduler.discardInvalid()
        try { runCleanup(node.cleanup) } catch (t: Throwable) { failures += t }
        views.remove(node.view)
        node.parent?.let { parent ->
            unreserve(parent, node.key)
            if (node.ownership != NativeOwnership.BORROWED && node.view.parent === parent) {
                try { parent.removeView(node.view) } catch (t: Throwable) { failures += t }
            }
        }
        throwCombined(failures)
    }

    /** accepted is observable immediately; completed only after owner cleanup finishes. */
    fun requestClose(): CloseRequest {
        if (closeRequested.compareAndSet(false, true)) {
            if (Looper.myLooper() === Looper.getMainLooper()) close()
            else if (!handler.post { try { close() } catch (_: Throwable) { /* failure is retained in the receipt */ } }) {
                closeReceipt.failure = IllegalStateException("Owner looper rejected close")
            }
        }
        return closeReceipt
    }

    override fun close() {
        assertOwner()
        if (state == SessionState.DISPOSED) return
        closeRequested.set(true); state = SessionState.DISPOSED; attachDomain.advance()
        val failures = mutableListOf<Throwable>()
        try { scheduler.close() } catch (t: Throwable) { failures += t }
        nodes.keys.toList().asReversed().forEach { id -> nodes[id]?.let { node -> try { release(ViewRef(this, id, node.key, node.component)) } catch (t: Throwable) { failures += t } } }
        try { runCleanup(sessionCleanup) } catch (t: Throwable) { failures += t }
        nodes.clear(); parentKeys.clear(); views.clear(); environmentValue = null
        closeReceipt.completed = true
        if (failures.isNotEmpty()) { closeReceipt.failure = failures.first(); throwCombined(failures) }
    }

    private fun runCleanup(actions: MutableList<() -> Unit>) {
        val pending = actions.toList().asReversed(); actions.clear()
        val failures = mutableListOf<Throwable>()
        pending.forEach { try { it() } catch (t: Throwable) { failures += t } }
        throwCombined(failures)
    }
    private fun throwCombined(failures: List<Throwable>) {
        if (failures.isNotEmpty()) { val first = failures.first(); failures.drop(1).forEach(first::addSuppressed); throw first }
    }
}
