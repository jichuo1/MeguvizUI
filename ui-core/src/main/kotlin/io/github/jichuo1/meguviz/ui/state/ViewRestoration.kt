package io.github.jichuo1.meguviz.ui.state

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef

data class ViewPresentation(val focused: Boolean, val scrollX: Int, val scrollY: Int, val selectionStart: Int?, val selectionEnd: Int?)

class RestorationSnapshot(ids: Map<NodeIdentity, Int>, presentation: Map<NodeIdentity, ViewPresentation>) {
    val ids = ids.toMap()
    val presentation = presentation.toMap()
    init { StableIdStore(ids); require(presentation.keys.all { it in ids }) }

    fun toBundle(): Bundle = Bundle().apply {
        putInt("schema", 1)
        putParcelableArrayList("nodes", ArrayList(ids.map { (identity, id) -> Bundle().apply {
            putString("path", identity.path); putString("component", identity.component); putInt("componentSchema", identity.schema); putInt("id", id)
            presentation[identity]?.let { state ->
                putBoolean("presentation", true); putBoolean("focused", state.focused)
                putInt("scrollX", state.scrollX); putInt("scrollY", state.scrollY)
                state.selectionStart?.let { putInt("selectionStart", it) }; state.selectionEnd?.let { putInt("selectionEnd", it) }
            }
        } }))
    }

    companion object {
        @Suppress("DEPRECATION")
        fun fromBundle(bundle: Bundle): RestorationSnapshot {
            require(bundle.getInt("schema") == 1) { "Unknown restoration schema" }
            val ids = linkedMapOf<NodeIdentity, Int>()
            val presentation = linkedMapOf<NodeIdentity, ViewPresentation>()
            val nodes = requireNotNull(bundle.getParcelableArrayList<Bundle>("nodes"))
            require(nodes.size <= 10000) { "Restoration node limit exceeded" }
            nodes.forEach { node ->
                val identity = NodeIdentity(requireNotNull(node.getString("path")), requireNotNull(node.getString("component")), node.getInt("componentSchema"))
                require(ids.put(identity, node.getInt("id")) == null) { "Duplicate restoration node" }
                if (node.getBoolean("presentation")) presentation[identity] = ViewPresentation(
                    node.getBoolean("focused"), node.getInt("scrollX"), node.getInt("scrollY"),
                    if (node.containsKey("selectionStart")) node.getInt("selectionStart") else null,
                    if (node.containsKey("selectionEnd")) node.getInt("selectionEnd") else null,
                )
            }
            return RestorationSnapshot(ids, presentation)
        }
    }
}

/** Apply the authoritative model before register/restoreAfterLayout. Text never comes from UI snapshots. */
class ViewRestoration(private val session: UiSession, restored: RestorationSnapshot? = null) : AutoCloseable {
    private val ids = StableIdStore(restored?.ids.orEmpty())
    private var saved = restored?.presentation.orEmpty()
    private val refs = linkedMapOf<NodeIdentity, ViewRef<*>>()
    private var interactionRevision = 0L
    private var closed = false
    private val callbacks = mutableListOf<() -> Unit>()
    init { session.onClose { close() } }

    /** Call from the application's input/event controller before recording a new user intent. */
    fun userInteracted() { session.assertOwner(); check(!closed); interactionRevision++; cancelPending() }

    fun register(path: String, ref: ViewRef<*>, root: View) {
        session.assertOwner(); check(!closed)
        val identity = NodeIdentity(path, ref.component.id, ref.component.schema)
        check(refs.keys.none { it.path == path }) { "Duplicate restore path" }
        val view = ref.get()
        val occupied = mutableSetOf<Int>()
        fun collect(node: View) {
            if (node !== view && node.id != View.NO_ID) occupied += node.id
            if (node is ViewGroup) for (index in 0 until node.childCount) collect(node.getChildAt(index))
        }
        collect(root)
        check(view.id == View.NO_ID || ids.snapshot()[identity] == view.id) { "Explicit native ID must be managed by the caller's hierarchy restoration" }
        view.id = ids.idFor(identity, occupied)
        refs[identity] = ref
    }

    fun snapshot(): RestorationSnapshot {
        session.assertOwner(); check(!closed)
        return RestorationSnapshot(ids.snapshot(), refs.mapValues { (_, ref) ->
            val view = ref.get()
            val edit = view as? EditText
            ViewPresentation(view.hasFocus(), view.scrollX, view.scrollY, edit?.selectionStart, edit?.selectionEnd)
        })
    }

    fun restoreAfterLayout(root: View) {
        session.assertOwner(); check(!closed); cancelPending()
        val expectedInput = interactionRevision
        val pending = refs.map { (identity, ref) -> Triple(ref, ref.token(resources = true), saved[identity]) }
        val observer = root.viewTreeObserver
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
        var canceled = false
        val cancel = {
            if (!canceled) {
                canceled = true
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
                else root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (root.isLaidOut) {
                cancel()
                if (!closed && interactionRevision == expectedInput) pending.forEach { (ref, token, state) ->
                    if (state != null && token.isCurrent()) {
                        val view = ref.get()
                        view.scrollTo(state.scrollX, state.scrollY)
                        if (view is EditText && state.selectionStart != null && state.selectionEnd != null) {
                            val length = view.text.length
                            if (state.selectionStart in 0..length && state.selectionEnd in 0..length) view.setSelection(state.selectionStart, state.selectionEnd)
                        }
                        if (state.focused) view.requestFocus()
                    }
                }
                saved = emptyMap()
            }
        }
        callbacks += cancel
        observer.addOnGlobalLayoutListener(listener)
        root.requestLayout()
    }

    private fun cancelPending() { callbacks.toList().forEach { it() }; callbacks.clear() }
    override fun close() {
        session.assertOwner()
        if (closed) return
        closed = true; cancelPending(); refs.clear(); saved = emptyMap()
    }
}
