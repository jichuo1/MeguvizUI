package io.github.jichuo1.meguviz.ui.recycler

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.jichuo1.meguviz.ui.EpochDomain
import io.github.jichuo1.meguviz.ui.EpochToken
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** Marks the constructor that copies only the submitted list, never its elements. */
private object ImmutableModels : (Any) -> Any { override fun invoke(p1: Any): Any = p1 }

/**
 * Models returned by [snapshotItem], including their diff fields, must be immutable.
 * [stableId] and [itemCallback] run on [background]; invalid IDs are reported to the failure callback.
 */
abstract class UiListAdapter<T : Any, H : RecyclerView.ViewHolder>(
    private val background: Executor,
    private val snapshotItem: (T) -> T,
    private val stableId: (T) -> Long,
    private val itemCallback: DiffUtil.ItemCallback<T>,
    onDiffFailure: (Throwable) -> Unit,
) : RecyclerView.Adapter<H>(), AutoCloseable {
    /**
     * For models the caller declares immutable, including every field used by [stableId] and
     * [itemCallback]. Each submission copies the list in one bulk array copy and calls no
     * per-item function on the owner thread; mutating a submitted element is a caller error.
     */
    @Suppress("UNCHECKED_CAST")
    constructor(
        background: Executor,
        stableId: (T) -> Long,
        itemCallback: DiffUtil.ItemCallback<T>,
        onDiffFailure: (Throwable) -> Unit,
    ) : this(background, ImmutableModels as (T) -> T, stableId, itemCallback, onDiffFailure)

    private class Change<T>(val old: List<T>, val oldIds: LongArray, val next: List<T>)
    private class Diff(val ids: LongArray, val result: DiffUtil.DiffResult)
    private val copyOnly = snapshotItem === ImmutableModels
    private val handler = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<H, EpochDomain>()
    private val attached = Collections.newSetFromMap(IdentityHashMap<RecyclerView, Boolean>())
    private var items: List<T> = emptyList()
    private var itemIds = LongArray(0)
    private var failureHandler: ((Throwable) -> Unit)? = onDiffFailure
    @Volatile private var closed = false
    private val worker = LatestWork<Change<T>, Diff>(background, Executor { command ->
        synchronized(handler) {
            if (!closed) check(handler.post(command)) { "Main looper rejected list result" }
        }
    }, { change ->
        val ids = LongArray(change.next.size) { stableId(change.next[it]) }
        val sorted = ids.copyOf().also { it.sort() }
        for (index in sorted.indices) {
            require(sorted[index] != RecyclerView.NO_ID && (index == 0 || sorted[index] != sorted[index - 1])) {
                "Stable IDs must be unique and not NO_ID"
            }
        }
        Diff(ids, DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = change.old.size
            override fun getNewListSize() = change.next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val same = itemCallback.areItemsTheSame(change.old[oldItemPosition], change.next[newItemPosition])
                check(same == (change.oldIds[oldItemPosition] == ids[newItemPosition])) { "Diff identity must agree with stable IDs" }
                return same
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                itemCallback.areContentsTheSame(change.old[oldItemPosition], change.next[newItemPosition])
            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                itemCallback.getChangePayload(change.old[oldItemPosition], change.next[newItemPosition])
        }))
    }, { change, diff ->
        assertOwner()
        if (!closed) { items = change.next; itemIds = diff.ids; diff.result.dispatchUpdatesTo(this) }
    }, { error -> assertOwner(); if (!closed) failureHandler?.invoke(error) })

    init { assertOwner(); setHasStableIds(true) }

    /**
     * Snapshots immediately on the owner thread (only the list itself for the immutable-model
     * constructor); same ID must represent the same item.
     * IDs are validated with the diff, so a list with duplicate or NO_ID IDs is reported to the
     * failure callback and never applied. A list superseded before its diff starts is not validated.
     */
    fun submitList(input: List<T>) {
        assertOwner(); check(!closed)
        val snapshot = if (copyOnly) ArrayList(input) else ArrayList<T>(input.size).also { copy ->
            for (item in input) copy.add(snapshotItem(item))
        }
        worker.submit(Change(items, itemIds, Collections.unmodifiableList(snapshot)))
    }

    final override fun getItemCount() = items.size
    final override fun getItemId(position: Int): Long = itemIds[position]
    protected fun item(position: Int): T = items[position]

    final override fun onBindViewHolder(holder: H, position: Int) = bind(holder, position, emptyList())
    final override fun onBindViewHolder(holder: H, position: Int, payloads: MutableList<Any>) = bind(holder, position, payloads)
    private fun bind(holder: H, position: Int, payloads: List<Any>) {
        assertOwner(); check(!closed)
        bindings.getOrPut(holder) { EpochDomain() }.advance()
        onRebind(holder)
        if (closed) return
        if (payloads.isEmpty()) bindFull(holder, item(position)) else bindPartial(holder, item(position), payloads)
    }

    /** Release the previous item's UiSession bindings here, for example with UiSession.rebind(ref). */
    protected open fun onRebind(holder: H) = Unit
    protected abstract fun bindFull(holder: H, item: T)
    /** Default is a full bind; callers overriding this must handle merged payloads. */
    protected open fun bindPartial(holder: H, item: T, payloads: List<Any>) = bindFull(holder, item)

    /** Capture after bind; check on the main thread before applying a delayed item result. */
    protected fun bindingToken(holder: H): () -> Boolean {
        assertOwner()
        val domain = checkNotNull(bindings[holder]) { "Holder has not been bound" }
        val token: EpochToken = domain.token()
        return { assertOwner(); !closed && bindings[holder] === domain && domain.token() == token }
    }

    final override fun onViewRecycled(holder: H) {
        assertOwner()
        bindings.remove(holder)?.advance()
        onRebind(holder)
        onRecycled(holder)
        super.onViewRecycled(holder)
    }
    protected open fun onRecycled(holder: H) = Unit

    final override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        assertOwner(); check(!closed)
        attached.add(recyclerView)
        super.onAttachedToRecyclerView(recyclerView)
    }

    final override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        assertOwner()
        attached.remove(recyclerView)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun close() {
        assertOwner()
        if (closed) return
        check(attached.isEmpty()) { "Detach the adapter from every RecyclerView before close" }
        synchronized(handler) {
            closed = true
            handler.removeCallbacksAndMessages(null)
        }
        worker.close()
        failureHandler = null
        bindings.values.forEach(EpochDomain::advance)
        val holders = bindings.keys.toList()
        bindings.clear()
        items = emptyList()
        itemIds = LongArray(0)
        var failure: Throwable? = null
        holders.forEach { holder ->
            try { onRebind(holder) } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun assertOwner() { check(Looper.myLooper() === Looper.getMainLooper()) { "Main thread required" } }
}
