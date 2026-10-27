package io.github.jichuo1.meguviz.ui.recycler

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class RecyclerInstrumentation : Instrumentation() {
    private data class Item(val id: Long, val label: String)
    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private class Queue : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = checkNotNull(tasks.poll()) { "Expected background diff" }.run()
    }
    private class Adapter(background: Executor, failures: MutableList<Throwable>) : UiListAdapter<Item, Holder>(
        background, { it.copy() }, { it.id }, object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
            override fun getChangePayload(oldItem: Item, newItem: Item): Any = "label"
        }, { failures += it },
    ) {
        var fullBinds = 0
        var cleanups = 0
        lateinit var lastToken: () -> Boolean
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
        override fun onRebind(holder: Holder) { cleanups++ }
        override fun bindFull(holder: Holder, item: Item) { fullBinds++; holder.text.text = item.label; lastToken = bindingToken(holder) }
    }
    private class ImmutableAdapter(background: Executor, failures: MutableList<Throwable>) : UiListAdapter<Item, Holder>(
        background, { it.id }, object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }, { failures += it },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
        override fun bindFull(holder: Holder, item: Item) { holder.text.text = item.label }
    }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var failure: Throwable? = null
        sendStatus(1, Bundle().apply { putString("class", RecyclerInstrumentation::class.java.name); putString("test", "real_recycler_diff_payload_recycle"); putInt("numtests", 1); putInt("current", 1) })
        val background = Queue()
        val failures = mutableListOf<Throwable>()
        lateinit var recycler: RecyclerView
        lateinit var adapter: Adapter
        var opened = false
        try {
            main {
                recycler = RecyclerView(targetContext)
                adapter = Adapter(background, failures)
                recycler.adapter = adapter
                opened = true
                adapter.submitList(listOf(Item(1, "a"), Item(1, "duplicate")))
            }
            background.run(); waitForIdleSync()
            main {
                check(failures.singleOrNull() is IllegalArgumentException && adapter.itemCount == 0) { "Duplicate IDs must be reported, not applied" }
                failures.clear()
                adapter.submitList(listOf(Item(1, "old"), Item(2, "second")))
                adapter.submitList(listOf(Item(2, "moved"), Item(1, "new")))
            }
            background.run(); waitForIdleSync()
            background.run(); waitForIdleSync()
            main {
                check(adapter.itemCount == 2 && adapter.getItemId(0) == 2L && adapter.getItemId(1) == 1L)
                val holder = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(holder, 0, mutableListOf())
                check(holder.text.text.toString() == "moved")
                val first = adapter.lastToken
                adapter.onBindViewHolder(holder, 1, mutableListOf("label"))
                check(!first() && holder.text.text.toString() == "new" && adapter.fullBinds == 2)
                val second = adapter.lastToken
                adapter.onViewRecycled(holder)
                check(!second())
                adapter.onBindViewHolder(holder, 0, mutableListOf())
                check(runCatching { adapter.close() }.isFailure)
            }
            main { adapter.submitList(listOf(Item(1, "after move"), Item(2, "moved"))) }
            background.run(); waitForIdleSync()
            main { check(adapter.getItemId(0) == 1L && adapter.getItemId(1) == 2L && failures.isEmpty()) }
            // The immutable-model constructor still isolates the submitted list from later list mutation.
            val source = mutableListOf(Item(7, "seven"), Item(8, "eight"))
            lateinit var immutable: ImmutableAdapter
            main { immutable = ImmutableAdapter(background, failures); immutable.submitList(source); source.clear(); source += Item(9, "nine") }
            background.run(); waitForIdleSync()
            main {
                check(immutable.itemCount == 2 && immutable.getItemId(0) == 7L && immutable.getItemId(1) == 8L && failures.isEmpty())
                immutable.submitList(listOf(Item(7, "seven"), Item(7, "duplicate")))
            }
            background.run(); waitForIdleSync()
            main {
                check(failures.singleOrNull() is IllegalArgumentException && immutable.itemCount == 2)
                failures.clear(); immutable.close()
            }
        } catch (error: Throwable) { failure = error }
        finally {
            if (opened) try { main { recycler.adapter = null; val before = adapter.cleanups; adapter.close(); check(adapter.cleanups == before + 1) } }
            catch (error: Throwable) { if (failure == null) failure = error }
        }
        sendStatus(if (failure == null) 0 else -2, Bundle().apply {
            putString("class", RecyclerInstrumentation::class.java.name); putString("test", "real_recycler_diff_payload_recycle"); putInt("numtests", 1); putInt("current", 1)
            failure?.let { putString("stack", it.stackTraceToString()) }
        })
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "1 test, ${if (failure == null) 0 else 1} failures\n") })
    }
}
