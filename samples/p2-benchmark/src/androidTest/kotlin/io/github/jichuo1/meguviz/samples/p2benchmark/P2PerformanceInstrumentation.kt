package io.github.jichuo1.meguviz.samples.p2benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.PrecomputedText
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.layouts.nativeRow
import io.github.jichuo1.meguviz.ui.plan.PlanComponent
import io.github.jichuo1.meguviz.ui.plan.PlanNode
import io.github.jichuo1.meguviz.ui.plan.UiPlan
import io.github.jichuo1.meguviz.ui.plan.resolve
import io.github.jichuo1.meguviz.ui.plan.validate
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmStop
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmSubmission
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmWork
import io.github.jichuo1.meguviz.ui.prewarm.UiPrewarmer
import io.github.jichuo1.meguviz.ui.recycler.UiListAdapter
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import io.github.jichuo1.meguviz.ui.text.PrecomputedTextBinding
import io.github.jichuo1.meguviz.ui.widgets.FramePolicy
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.LinearPolicy
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.MatchParent
import io.github.jichuo1.meguviz.ui.widgets.WrapContent
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same-input A/B timing for the six P2 profiles. A is the path an application would use
 * without the module; B uses the module. Groups alternate ABBA per iteration, warmup samples
 * are discarded, and every sample checks the produced native result before it is recorded.
 * Raw nanosecond samples leave the device as status code 2 bundles; no verdict is made here.
 */
class P2PerformanceInstrumentation : Instrumentation() {
    private class Sample(val nanos: Long, val aux: Map<String, Long> = emptyMap())
    private class Experiment(
        val profile: String,
        val scenario: String,
        val metric: String,
        val groupA: String,
        val groupB: String,
        val sample: (Char) -> Sample,
    )

    private var warmup = 20
    private var samples = 100
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        warmup = arguments?.getString("warmup")?.toIntOrNull() ?: warmup
        samples = arguments?.getString("samples")?.toIntOrNull() ?: samples
        start()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    override fun onStart() {
        var failures = 0
        // A visible foreground window keeps the process out of the background freezer and vsync running.
        val host = try {
            startActivitySync(Intent(targetContext, BenchmarkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
            return
        }
        waitForIdleSync()
        val experiments = try {
            check(warmup in 0..1000 && samples in 1..10000) { "Sample bounds exceeded" }
            listOf(plan(), codegen(), recycler(), text(), layouts(), prewarm())
        } catch (error: Throwable) {
            runOnMainSync { host.finish() }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
            return
        }
        experiments.forEachIndexed { index, experiment ->
            fun status() = Bundle().apply {
                putString("class", P2PerformanceInstrumentation::class.java.name)
                putString("test", experiment.profile + "_" + experiment.scenario)
                putInt("numtests", experiments.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            try { sendStatus(2, Bundle().apply { putString("p2perf", measure(experiment).toString()) }) }
            catch (error: Throwable) { failure = error }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        runOnMainSync { host.finish() }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "P2 performance: ${experiments.size} experiments, $failures failures\n")
        })
    }

    private fun measure(experiment: Experiment): JSONObject {
        val values = mapOf('A' to mutableListOf<Long>(), 'B' to mutableListOf())
        val aux = mapOf('A' to linkedMapOf<String, MutableList<Long>>(), 'B' to linkedMapOf())
        val started = System.nanoTime()
        for (iteration in 0 until warmup + samples) {
            for (group in if (iteration % 2 == 0) "AB" else "BA") {
                Runtime.getRuntime().gc()
                waitForIdleSync()
                val sample = experiment.sample(group)
                check(sample.nanos > 0) { "Non-positive sample" }
                if (iteration >= warmup) {
                    values.getValue(group) += sample.nanos
                    sample.aux.forEach { (name, value) -> aux.getValue(group).getOrPut(name) { mutableListOf() } += value }
                }
            }
        }
        fun series(group: Char) = JSONObject().apply {
            put("samples", JSONArray(values.getValue(group)))
            put("aux", JSONObject().apply { aux.getValue(group).forEach { (name, list) -> put(name, JSONArray(list)) } })
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("profile", experiment.profile)
            put("scenario", experiment.scenario)
            put("metric", experiment.metric)
            put("unit", "ns")
            put("warmup", warmup)
            put("order", "ABBA-interleaved")
            put("api", Build.VERSION.SDK_INT)
            put("elapsed_ns", System.nanoTime() - started)
            put("A", series('A').put("description", experiment.groupA))
            put("B", series('B').put("description", experiment.groupB))
        }
    }

    // --- plan: dynamic plan pipeline against the equivalent direct DSL page ---------------
    private fun plan(): Experiment {
        val registry = main {
            RegistrySnapshot.install(listOf(
                FactoryRegistration(ExtensionDescriptor("linear", "1"), Framework.linear, Framework.linearFactory),
                FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, Framework.textFactory),
            ))
        }
        val components = listOf(
            PlanComponent(Framework.linear, "frame", FrameLayout::class.java, FramePolicy, { w, h -> frameParams(w, h) }, childKind = "linear"),
            PlanComponent(Framework.text, "linear", LinearLayout::class.java, LinearPolicy, { w, h -> linearParams(w, h) }),
        )
        val groups = 6
        val leaves = 8
        val description = UiPlan(rootKind = "frame", nodes = (0 until groups).map { g ->
            PlanNode("g$g", Framework.linear.id, children = (0 until leaves).map { PlanNode("t$it", Framework.text.id) })
        })
        return Experiment("plan", "48-node-page-construction", "main_thread_construction",
            "direct DSL mount of the same 6x8 tree", "validate+resolve+bind+mount of the same 6x8 UiPlan") { group ->
            main {
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    val root = FrameLayout(targetContext)
                    val started = System.nanoTime()
                    if (group == 'A') {
                        val scope = FrameScope(session, root)
                        repeat(groups) { g ->
                            scope.linear(ViewKey("g$g"), frameParams(MatchParent, WrapContent)) {
                                repeat(leaves) { text(ViewKey("t$it"), linearParams(MatchParent, WrapContent)) }
                            }
                        }
                    } else {
                        description.validate().resolve(registry, components).bind(session, root, "frame").mount()
                    }
                    val elapsed = System.nanoTime() - started
                    check(root.childCount == groups && (0 until groups).all { (root.getChildAt(it) as ViewGroup).childCount == leaves })
                    Sample(elapsed)
                } finally { session.close() }
            }
        }
    }

    // --- codegen: generated registration against the hand-written one ---------------------
    private fun codegen(): Experiment {
        val factories = main {
            mapOf(
                'A' to RegistrySnapshot.install(listOf(HandWrittenBenchLabel.registration())).resolve(HandWrittenBenchLabel.type),
                'B' to RegistrySnapshot.install(listOf(BenchLabelUiGenerated.registration())).resolve(BenchLabelUiGenerated.type),
            )
        }
        val types = mapOf('A' to HandWrittenBenchLabel.type, 'B' to BenchLabelUiGenerated.type)
        val count = 64
        return Experiment("codegen", "64-view-registry-mount", "main_thread_construction",
            "hand-written FactoryRegistration resolved from a registry", "KSP-generated registration resolved from a registry") { group ->
            main {
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    val root = FrameLayout(targetContext)
                    val scope = FrameScope(session, root)
                    val type = types.getValue(group)
                    val factory = factories.getValue(group)
                    val started = System.nanoTime()
                    repeat(count) { scope.view(ViewKey("l$it"), type, frameParams(), factory) }
                    val elapsed = System.nanoTime() - started
                    check(root.childCount == count && (0 until count).all { root.getChildAt(it) is BenchLabel })
                    Sample(elapsed)
                } finally { session.close() }
            }
        }
    }

    // --- recycler: burst submissions, latest-only adapter against official ListAdapter -----
    private data class Item(val id: Long, val label: String)
    private class Holder(view: TextView) : RecyclerView.ViewHolder(view)
    private object Callback : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
    }
    private class BaselineAdapter(config: AsyncDifferConfig<Item>) : ListAdapter<Item, Holder>(config) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
        override fun onBindViewHolder(holder: Holder, position: Int) { (holder.itemView as TextView).text = getItem(position).label }
    }
    // Item is an immutable data class, the same assumption ListAdapter makes for group A.
    private class LatestAdapter(background: java.util.concurrent.Executor) : UiListAdapter<Item, Holder>(
        background, { it.id }, Callback, { throw AssertionError(it) },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
        override fun bindFull(holder: Holder, item: Item) { (holder.itemView as TextView).text = item.label }
    }

    private fun recycler(): Experiment {
        val size = 2000
        val bursts = 16
        val base = (0 until size).map { Item(it.toLong(), "item $it") }
        val lists = (0 until bursts).map { k ->
            val random = Random(k)
            val next = base.map { if (random.nextInt(20) == 0) it.copy(label = "${it.label} v$k") else it }.toMutableList()
            repeat(40) { val from = random.nextInt(next.size); next.add(random.nextInt(next.size), next.removeAt(from)) }
            next += (0..k).map { Item((size + it).toLong(), "extra $it") }
            next.toList()
        }
        val finalSize = lists.last().size
        val diffs = AtomicInteger()
        val pool: ExecutorService = Executors.newSingleThreadExecutor()
        val background = java.util.concurrent.Executor { command -> pool.execute { diffs.incrementAndGet(); command.run() } }
        fun await(latch: CountDownLatch) = check(latch.await(60, TimeUnit.SECONDS)) { "List did not converge" }
        return Experiment("recycler", "16-submission-burst-2000-items", "burst_to_final_list_applied",
            "official ListAdapter/AsyncListDiffer, same single-thread executor", "UiListAdapter immutable-model constructor, latest-only diff, same single-thread executor") { group ->
            val ready = CountDownLatch(1)
            val converged = CountDownLatch(1)
            var finished = 0L
            var started = 0L
            var submitNanos = 0L
            var diffsBefore = 0
            fun markConverged() = handler.post { finished = System.nanoTime(); converged.countDown() }
            if (group == 'A') {
                val config = AsyncDifferConfig.Builder(Callback).setBackgroundThreadExecutor(background).build()
                lateinit var adapter: BaselineAdapter
                main { adapter = BaselineAdapter(config); adapter.submitList(base) { ready.countDown() } }
                await(ready)
                main {
                    diffsBefore = diffs.get()
                    started = System.nanoTime()
                    lists.forEachIndexed { index, list ->
                        if (index == lists.lastIndex) adapter.submitList(list) { markConverged() } else adapter.submitList(list)
                    }
                    submitNanos = System.nanoTime() - started
                }
                await(converged)
                main { check(adapter.itemCount == finalSize && adapter.currentList == lists.last()) }
            } else {
                lateinit var adapter: LatestAdapter
                var target = size
                var signalled = false
                main {
                    adapter = LatestAdapter(background)
                    adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        fun changed() {
                            if (adapter.itemCount != target || signalled) return
                            if (target == size) { target = -1; ready.countDown() } else { signalled = true; markConverged() }
                        }
                        override fun onChanged() = changed()
                        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = changed()
                        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = changed()
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed()
                        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed()
                        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = changed()
                    })
                    adapter.submitList(base)
                }
                await(ready)
                main {
                    target = finalSize
                    diffsBefore = diffs.get()
                    started = System.nanoTime()
                    lists.forEach(adapter::submitList)
                    submitNanos = System.nanoTime() - started
                }
                await(converged)
                main {
                    check(adapter.itemCount == finalSize && (0 until finalSize).all { adapter.getItemId(it) == lists.last()[it].id })
                    adapter.close()
                }
            }
            Sample(finished - started, mapOf("diffs_executed" to (diffs.get() - diffsBefore).toLong(), "main_submit_ns" to submitNanos))
        }
    }

    // --- text: long plain label applied with and without background precomputation --------
    private class Driver : UiFrameDriver {
        override val owner = "p2-performance"
        @Volatile var callback: ((FrameBudget) -> Unit)? = null
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            check(this.callback == null); this.callback = callback
            return FrameTicket { this.callback = null }
        }
        fun tick() { val item = checkNotNull(callback) { "No frame requested" }; callback = null; item(FrameBudget(System.nanoTime())) }
    }

    private fun text(): Experiment {
        val random = Random(37)
        val words = listOf("settings", "account", "privacy", "network", "storage", "display", "language", "notification", "background", "permission")
        val label = buildString { while (length < 3000) { append(words[random.nextInt(words.size)]); append(' ') } }.trimEnd()
        val pool = Executors.newSingleThreadExecutor()
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return Experiment("text", "3000-char-plain-label-apply", "main_thread_apply_measure_layout",
            "PrecomputedTextBinding disabled: normal setter in the frame", "PrecomputedTextBinding enabled: background PrecomputedText, applied in the frame") { group ->
            check(group == 'A' || Build.VERSION.SDK_INT >= 28) { "Precomputation requires API 28" }
            val driver = Driver()
            lateinit var session: UiSession
            lateinit var view: TextView
            lateinit var binding: PrecomputedTextBinding<TextView>
            var submitted = 0L
            main {
                session = UiSession(CreationEnvironment(targetContext), driver)
                view = TextView(targetContext).apply { textSize = 16f }
                val ref = session.borrow(view, ViewKey("text"), ComponentType("text", TextView::class.java))
                binding = PrecomputedTextBinding(session, ref, pool, { throw AssertionError(it) }, enabled = group == 'B')
                submitted = System.nanoTime()
                check(binding.submit(label) == (group == 'B'))
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (driver.callback == null) {
                    check(System.nanoTime() < deadline) { "Text result was not scheduled" }
                    Thread.sleep(1); waitForIdleSync()
                }
                val ready = System.nanoTime() - submitted
                main {
                    val started = System.nanoTime()
                    driver.tick()
                    view.measure(width, height)
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val elapsed = System.nanoTime() - started
                    check(view.text.toString() == label && view.lineCount > 1)
                    check((group == 'B') == (Build.VERSION.SDK_INT >= 28 && view.text is PrecomputedText))
                    Sample(elapsed, mapOf("submit_to_frame_request_ns" to ready))
                }
            } finally { main { binding.close(); session.close() } }
        }
    }

    // --- layouts: bounded native row against the equivalent horizontal LinearLayout -------
    private fun layouts(): Experiment {
        val rows = 8
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return Experiment("layouts", "8-row-construct-measure-layout", "main_thread_construct_measure_layout",
            "horizontal linear() rows", "nativeRow() rows with the same children and configuration") { group ->
            main {
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    val root = LinearLayout(targetContext).apply { orientation = LinearLayout.VERTICAL }
                    val scope = LinearScope(session, root)
                    val children: LinearScope.() -> Unit = {
                        repeat(3) { text(ViewKey("t$it"), linearParams(0, WrapContent) { weight = 1f; marginEnd = 8 }) { text = "Label $it" } }
                        repeat(2) { button(ViewKey("b$it"), linearParams(WrapContent, WrapContent)) { text = "OK" } }
                    }
                    val started = System.nanoTime()
                    repeat(rows) { r ->
                        if (group == 'A') {
                            scope.linear(ViewKey("r$r"), linearParams(MatchParent, WrapContent), configure = {
                                orientation = LinearLayout.HORIZONTAL; minimumHeight = 64
                            }, children = children)
                        } else {
                            scope.nativeRow(ViewKey("r$r"), linearParams(MatchParent, WrapContent), configure = { minimumHeight = 64 }, children = children)
                        }
                    }
                    root.measure(width, height)
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val elapsed = System.nanoTime() - started
                    check(root.childCount == rows && (0 until rows).all { (root.getChildAt(it) as LinearLayout).childCount == 5 })
                    Sample(elapsed)
                } finally { session.close() }
            }
        }
    }

    // --- prewarm: first switch to a 48-row page, lazy against bounded prewarm -------------
    private fun prewarm(): Experiment {
        val rowsPerUnit = 4
        val units = 12
        val width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun LinearScope.rows(unit: Int) = repeat(rowsPerUnit) {
            val index = unit * rowsPerUnit + it
            text(ViewKey("t$index"), linearParams()) { text = "Row $index" }
        }
        return Experiment("prewarm", "48-row-page-first-switch", "main_thread_switch_frame",
            "lazy: construct the page in the switch frame", "UiPrewarmer: one 4-row unit per platform frame before the switch; a discarded page is built lazily") { group ->
            val built = CountDownLatch(1)
            lateinit var session: UiSession
            lateinit var host: FrameLayout
            lateinit var container: FrameLayout
            var discarded: PrewarmStop? = null
            var maxUnit = 0L
            var unitsRun = 0L
            var prewarmStarted = 0L
            var prewarmNanos = 0L
            main {
                session = UiSession(CreationEnvironment(targetContext))
                host = FrameLayout(targetContext)
                container = FrameLayout(targetContext)
                if (group == 'B') {
                    val prewarmer = UiPrewarmer.attach(session, maxUnitsPerWork = units + 1)
                    var page: io.github.jichuo1.meguviz.ui.ViewRef<LinearLayout>? = null
                    prewarmStarted = System.nanoTime()
                    val submission = prewarmer.submit(PrewarmWork(
                        isCurrent = { true },
                        step = {
                            val started = System.nanoTime()
                            val current = page ?: FrameScope(session, container).linear(ViewKey("page"), frameParams(), configure = {
                                orientation = LinearLayout.VERTICAL
                            }) {}.also { page = it }
                            LinearScope(session, current.get()).rows(unitsRun.toInt())
                            unitsRun++
                            maxUnit = maxOf(maxUnit, System.nanoTime() - started)
                            unitsRun.toInt() == units
                        },
                        ready = { prewarmNanos = System.nanoTime() - prewarmStarted; built.countDown() },
                        discard = { reason ->
                            discarded = reason
                            page?.let(session::release)
                            page = null
                            built.countDown()
                        },
                    ))
                    check(submission == PrewarmSubmission.Accepted) { "Prewarm rejected: $submission" }
                } else built.countDown()
            }
            try {
                check(built.await(10, TimeUnit.SECONDS)) { "Prewarm timed out" }
                check(discarded == null || discarded == PrewarmStop.OVER_BUDGET) { "Prewarm discarded: $discarded" }
                main {
                    val started = System.nanoTime()
                    if (group == 'A' || discarded != null) {
                        check(container.childCount == 0)
                        FrameScope(session, container).linear(ViewKey("page"), frameParams(), configure = {
                            orientation = LinearLayout.VERTICAL
                        }) { repeat(units) { rows(it) } }
                    }
                    host.addView(container, FrameLayout.LayoutParams(MatchParent, WrapContent))
                    host.measure(width, height)
                    host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                    val elapsed = System.nanoTime() - started
                    check(((container.getChildAt(0) as LinearLayout).childCount) == units * rowsPerUnit)
                    Sample(elapsed, if (group == 'B') mapOf("max_unit_ns" to maxUnit, "prewarm_wall_ns" to prewarmNanos, "units" to unitsRun,
                        "discarded_over_budget" to if (discarded == null) 0L else 1L) else emptyMap())
                }
            } finally { main { host.removeAllViews(); session.close() } }
        }
    }
}
