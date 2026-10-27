package io.github.jichuo1.meguviz.samples.p2matrix

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.Layout
import android.text.PrecomputedText
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
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
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import io.github.jichuo1.meguviz.ui.text.PrecomputedTextBinding
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.LinearPolicy
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.WrapContent
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * P2 behavior matrix on a real device or emulator. Each case asserts native behavior and sends the
 * observed values as a status code 2 "evidence" bundle. System settings are never modified:
 * font scale, direction, locale and night mode are per-activity or per-context configuration overrides.
 */
class P2MatrixInstrumentation : Instrumentation() {
    private var only: String? = null
    private var repeat = 1

    /** `-e only <case> -e repeat <n>` runs one case n times for investigation; the gate runs every case once. */
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        only = arguments?.getString("only")
        repeat = arguments?.getString("repeat")?.toIntOrNull()?.coerceIn(1, 200) ?: 1
        start()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!main(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            SystemClock.sleep(20)
        }
    }

    /** waitForIdleSync without an unbounded wait: a stuck main thread becomes a labelled failure. */
    private fun idle(what: String = "main thread") {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { latch.countDown() }
        check(latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "Main thread blocked: $what" }
    }

    /** startActivitySync without an unbounded wait. */
    private fun startBounded(intent: Intent): Activity {
        var started: Activity? = null
        var failure: Throwable? = null
        val thread = Thread { try { started = startActivitySync(intent) } catch (error: Throwable) { failure = error } }
        thread.start(); thread.join(20_000)
        failure?.let { throw it }
        return checkNotNull(started) { "Activity launch did not complete within 20 s: ${intent.component?.shortClassName}" }
    }

    private fun dp(value: Float) = value * targetContext.resources.displayMetrics.density

    private fun <A : MatrixActivity> launch(type: Class<A>, configure: MatrixOverrides.() -> Unit = {}): A {
        MatrixOverrides.reset(); MatrixOverrides.configure()
        @Suppress("UNCHECKED_CAST")
        val activity = startBounded(Intent(targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as A
        try { settle(activity) } catch (failure: Throwable) {
            runCatching { close(activity) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return activity
    }

    private fun settle(activity: MatrixActivity) {
        try {
            waitFor("list and text") {
                activity.adapter.itemCount == MatrixActivity.ROWS && activity.recycler.childCount > 0 &&
                    (Build.VERSION.SDK_INT < 28 || activity.longText.text is PrecomputedText)
            }
        } catch (timeout: IllegalStateException) {
            val detail = main { "items=${activity.adapter.itemCount} children=${activity.recycler.childCount} " +
                "text=${activity.longText.text.javaClass.simpleName}(${activity.longText.text.length}) " +
                "dir=${activity.longText.layoutDirection} textDir=${if (Build.VERSION.SDK_INT >= 28) activity.longText.textMetricsParams.textDirection else null}" }
            throw IllegalStateException("${timeout.message}: $detail", timeout)
        }
        waitFor("window focus (another window may be covering the activity)") { activity.hasWindowFocus() }
        idle()
    }

    private fun close(activity: Activity) {
        main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; activity.finish() }
        // The platform destroys a finishing activity once the next one is idle, or after its 10 s idle timeout.
        waitFor("destroy", 30_000) { activity.isDestroyed }
        MatrixOverrides.reset()
    }

    private var currentCase = ""
    private fun evidence(name: String, value: JSONObject) =
        sendStatus(2, Bundle().apply { putString("matrix", JSONObject().put("case", if (only != null) currentCase else name).put("evidence", value).toString()) })

    private fun recreated(old: MatrixActivity, type: Class<out MatrixActivity>, action: () -> Unit): MatrixActivity {
        val monitor = addMonitor(type.name, null, false)
        try {
            action()
            val next = waitForMonitorWithTimeout(monitor, 15_000) as? MatrixActivity
            checkNotNull(next) { "Activity was not recreated" }
            check(next !== old)
            settle(next)
            return next
        } finally { removeMonitor(monitor) }
    }

    /** Returns the list anchor observed after each setup step, so a moved list is visible in evidence. */
    private fun prepareState(activity: MatrixActivity): JSONArray {
        val trace = JSONArray()
        fun mark(step: String) = main {
            val (position, offset) = activity.listAnchor()
            val focus = activity.currentFocus
            trace.put(JSONObject().put("step", step).put("position", position).put("offset", offset)
                .put("focus", (focus as? TextView)?.let { (it.contentDescription ?: it.text).toString() } ?: focus?.javaClass?.simpleName ?: "none"))
        }
        main { activity.toggle(3); activity.toggle(7) }
        waitFor("toggles applied") { activity.adapter.itemCount == MatrixActivity.ROWS && activity.toggled == setOf(3L, 7L) }
        main { (activity.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(40, 0) }
        waitFor("scroll") { (activity.recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() == 40 }
        mark("scrolled")
        sendKeyDownUpSync(KeyEvent.KEYCODE_TAB) // leave touch mode so focus can be placed and restored
        idle(); mark("after_tab")
        main { check(activity.findViewById<View>(registeredId(activity, "rowBButton")).requestFocus()) }
        idle(); mark("after_focus")
        return trace
    }

    private fun registeredId(activity: MatrixActivity, key: String): Int {
        val views = mutableListOf<View>()
        fun walk(view: View) { views += view; if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i)) }
        walk(activity.root)
        val label = when (key) { "rowBButton" -> activity.getString(R.string.row_second_action); "openPage" -> activity.getString(R.string.open_page); else -> error(key) }
        return views.filterIsInstance<Button>().single { it.text.toString() == label && it.parent !is RecyclerView && (it.parent as View).parent !is RecyclerView }.id
    }

    /** The restored list must show the anchor the old instance actually saved, not a precomputed target. */
    private fun checkRestored(before: MatrixActivity, after: MatrixActivity, focusId: Int, trace: JSONArray): JSONObject = main {
        check(after.restoredFromState) { "No saved state" }
        check(after.toggled == setOf(3L, 7L)) { "Model toggles lost: ${after.toggled}" }
        val saved = checkNotNull(before.savedAnchor) { "Old instance did not save" }
        val restored = after.listAnchor()
        check(restored.first == saved.first && kotlin.math.abs(restored.second - saved.second) <= 1) {
            "Scroll not restored: saved $saved, restored $restored, setup $trace"
        }
        check(after.currentFocus?.id == focusId) { "Focus not restored" }
        val visible = (0 until after.recycler.childCount).map { after.recycler.getChildViewHolder(after.recycler.getChildAt(it)) as MatrixActivity.RowHolder }
        visible.forEach { holder ->
            val id = after.adapter.getItemId(holder.bindingAdapterPosition)
            check(holder.action.text.toString() == after.getString(if (id in after.toggled) R.string.item_on else R.string.item_off)) { "Row $id shows stale state" }
        }
        check(after.restoredIds.isNotEmpty() && after.restoredIds.all { (path, id) -> after.findViewById<View>(id) != null }) { "Stable IDs not reapplied: ${after.restoredIds}" }
        JSONObject().put("setup", trace).put("saved_anchor", JSONArray(listOf(saved.first, saved.second)))
            .put("restored_anchor", JSONArray(listOf(restored.first, restored.second))).put("after", JSONObject(after.stateJson()))
            .put("stable_ids", JSONObject(after.restoredIds.mapValues { it.value }))
    }

    private fun MatrixActivity.stateJsonSafe(): String = runCatching { stateJson() }.getOrDefault("{}")

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "rotation_restores_model_scroll_focus_ids" to ::rotation,
            "activity_recreate_restores_model_scroll_focus_ids" to ::recreate,
            "accessibility_tree_labels_targets_heading_order" to ::accessibilityTree,
            "keyboard_tab_and_dpad_traversal" to ::keyboard,
            "font_scale_2_activity_no_clipping_targets" to { fontScaleActivity(2.0f) },
            "rtl_activity_mirrors_rows_and_list" to ::rtlActivity,
            "recycler_attached_animator_burst_while_scrolling" to ::recyclerBurst,
            "recycler_dropped_payload_full_bind" to ::recyclerDroppedPayload,
            "recycler_focus_kept_after_diff" to ::recyclerFocus,
            "text_precomputed_equals_plain_layout_matrix" to ::textMatrix,
            "layouts_font_scale_rtl_geometry_targets_focus_baseline" to ::layoutsMatrix,
            "plan_rtl_font_scale_geometry_and_accessibility_order" to ::planMatrix,
            "prewarm_low_memory_trim_releases_staged_page" to ::prewarmLowMemory,
            "prewarm_rotation_resource_change_releases_staged_page" to ::prewarmResourceChange,
            "prewarm_home_detach_and_unfinished_page_fallback" to ::prewarmDetachAndFallback,
        )
        only?.let { selected ->
            val body = checkNotNull(cases[selected]) { "Unknown case $selected" }
            cases.clear()
            repeat(repeat) { cases[selected] = body; if (repeat > 1) cases["$selected#${it + 1}"] = body }
            if (repeat > 1) cases.remove(selected)
        }
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", P2MatrixInstrumentation::class.java.name); putString("test", name)
                putInt("numtests", cases.size); putInt("current", index + 1)
            }
            sendStatus(1, status())
            currentCase = name
            var failure: Throwable? = null
            try { body() } catch (error: Throwable) { failure = error }
            finally { MatrixOverrides.reset() }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "P2 matrix: ${cases.size} cases, $failures failures\n")
        })
    }

    // --- restoration ------------------------------------------------------------------------
    private fun rotation() {
        val first = launch(MatrixActivity::class.java)
        // The display may still be in the orientation a previous run left; always request the other one.
        val landscape = main { first.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE }
        val rotated = try {
            val trace = prepareState(first)
            val focusId = main { checkNotNull(first.currentFocus).id }
            recreated(first, MatrixActivity::class.java) {
                main { first.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            } to (trace to focusId)
        } catch (failure: Throwable) {
            runCatching { close(first) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val (next, setup) = rotated
        try {
            val wanted = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            waitFor("rotated orientation") { next.resources.configuration.orientation == wanted }
            evidence("rotation_restores_model_scroll_focus_ids", checkRestored(first, next, setup.second, setup.first)
                .put("orientation", if (landscape) "landscape" else "portrait"))
        } finally { close(next) }
    }

    private fun recreate() {
        val first = launch(MatrixActivity::class.java)
        lateinit var trace: JSONArray
        var focusId = View.NO_ID
        val next = try {
            trace = prepareState(first)
            focusId = main { checkNotNull(first.currentFocus).id }
            recreated(first, MatrixActivity::class.java) { main { first.recreate() } }
        } catch (failure: Throwable) {
            runCatching { close(first) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try { evidence("activity_recreate_restores_model_scroll_focus_ids", checkRestored(first, next, focusId, trace)) }
        finally { close(next) }
    }

    // --- accessibility and keyboard ---------------------------------------------------------
    private fun accessibilityTree() {
        val activity = launch(MatrixActivity::class.java)
        try {
            val automation = uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            var rootNode: AccessibilityNodeInfo? = null
            var seen = emptyList<String>()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (rootNode == null) {
                check(SystemClock.uptimeMillis() < deadline) { "No accessibility root for the activity; windows=$seen" }
                SystemClock.sleep(100)
                val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                seen = roots.map { it.packageName?.toString() ?: "?" }
                rootNode = roots.firstOrNull { it.packageName == targetContext.packageName }
            }
            val order = JSONArray()
            val problems = mutableListOf<String>()
            var heading = false
            var collectionRows = -1
            val minimum = dp(48f) - 1
            fun visit(node: AccessibilityNodeInfo, viewport: Rect?) {
                // TalkBack speaks the content description when one is set, otherwise the text.
                val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                if (label.isNotBlank()) order.put(JSONObject().put("label", label.take(40)).put("class", node.className).put("clickable", node.isClickable)
                    .put("visible", node.isVisibleToUser).put("bounds", bounds.flattenToString()))
                // Nodes cut by a scrolling viewport edge report clipped bounds; TalkBack scrolls them in first.
                val clipped = viewport != null && (bounds.top <= viewport.top || bounds.bottom >= viewport.bottom)
                if (node.isClickable && node.isVisibleToUser && !clipped) {
                    if (label.isBlank()) problems += "unlabeled clickable ${node.className}"
                    if (bounds.height() < minimum || bounds.width() < minimum) problems += "small target '$label' $bounds"
                }
                if (Build.VERSION.SDK_INT >= 28 && label == activity.getString(R.string.title) && node.isHeading) heading = true
                if (node.className == RecyclerView::class.java.name) collectionRows = node.collectionInfo?.rowCount ?: -1
                val inner = if (node.isScrollable) bounds else viewport
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, inner) }
            }
            visit(checkNotNull(rootNode), null)
            val labels = (0 until order.length()).map { order.getJSONObject(it).getString("label") }
            val expected = listOf(R.string.title, R.string.plan_first, R.string.plan_second, R.string.generated_label,
                R.string.row_label, R.string.row_action, R.string.row_second_label, R.string.row_second_action, R.string.open_page)
                .map { activity.getString(it) }
            // Buttons expose their all-caps transformed text, which is what TalkBack reads.
            val positions = expected.map { wanted -> labels.indexOfFirst { it.equals(wanted, ignoreCase = true) } }
            check(positions.all { it >= 0 } && positions == positions.sorted()) { "Reading order differs: $labels" }
            check(labels.any { it.startsWith(activity.getString(R.string.long_text).take(20)) }) { "Precomputed text not exposed" }
            check(labels.indexOfFirst { it.equals(activity.getString(R.string.item_toggle, 0L), ignoreCase = true) } > positions.last()) { "List not after controls" }
            check(problems.isEmpty()) { problems.joinToString() }
            check(Build.VERSION.SDK_INT < 28 || heading) { "Title is not a heading" }
            check(collectionRows == MatrixActivity.ROWS) { "Collection rows $collectionRows" }
            evidence("accessibility_tree_labels_targets_heading_order", JSONObject().put("traversal", order)
                .put("heading", heading).put("collection_rows", collectionRows).put("min_target_px", minimum))
        } finally { close(activity) }
    }

    private fun focusLabel(activity: MatrixActivity): String = main {
        val focus = activity.currentFocus
        (focus as? TextView)?.let { (it.contentDescription ?: it.text).toString() } ?: focus?.javaClass?.simpleName ?: "none"
    }

    private fun keyboard() {
        val activity = launch(MatrixActivity::class.java)
        try {
            val sequence = mutableListOf<String>()
            focusLabel(activity).takeIf { main { activity.currentFocus is Button } }?.let { sequence += it }
            repeat(8) { sendKeyDownUpSync(KeyEvent.KEYCODE_TAB); idle(); sequence += focusLabel(activity) }
            val names = listOf(R.string.row_action, R.string.row_second_action, R.string.open_page).map { activity.getString(it) }
            val positions = names.map { wanted -> sequence.indexOfFirst { it.equals(wanted, ignoreCase = true) } }
            check(positions.all { it >= 0 } && positions == positions.sorted()) { "Tab order: $sequence" }
            val firstRow = sequence.indexOf(activity.getString(R.string.item_toggle, 0L))
            check(firstRow > positions.last()) { "Tab did not enter the list after controls: $sequence" }
            val dpad = mutableListOf(focusLabel(activity))
            repeat(3) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); idle(); dpad += focusLabel(activity) }
            val rows = dpad.map { label -> (0L until MatrixActivity.ROWS).firstOrNull { activity.getString(R.string.item_toggle, it) == label } }
            check(rows.all { it != null } && rows.zipWithNext().all { (a, b) -> b!! == a!! + 1 }) { "D-pad order: $dpad" }
            sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); idle()
            waitFor("keyboard toggle") { rows.last()!! in activity.toggled }
            evidence("keyboard_tab_and_dpad_traversal", JSONObject().put("tab", JSONArray(sequence)).put("dpad", JSONArray(dpad))
                .put("enter_toggled", rows.last()))
        } finally { close(activity) }
    }

    // --- font scale and RTL activities --------------------------------------------------------
    private fun textViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) { if (view is TextView && view.isShown) out += view; if (view is ViewGroup && view !is RecyclerView) for (i in 0 until view.childCount) walk(view.getChildAt(i)) }
        walk(root); return out
    }

    private fun fontScaleActivity(scale: Float) {
        val activity = launch(MatrixActivity::class.java) { fontScale = scale }
        try {
            val report = main {
                check(activity.resources.configuration.fontScale == scale)
                val problems = mutableListOf<String>()
                textViews(activity.root).forEach { view ->
                    val layout: Layout? = view.layout
                    if (view.height == 0 || view.bottom <= view.top) return@forEach // scrolled-off list area
                    if (layout == null || layout.height > view.height - view.totalPaddingTop - view.totalPaddingBottom + 1) problems += "clipped '${view.text.take(20)}'"
                    if (layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) problems += "ellipsized '${view.text.take(20)}'"
                    if (view is Button && view.height < dp(48f) - 1) problems += "small button '${view.text}'"
                }
                val rows = (0 until activity.content.childCount).map { activity.content.getChildAt(it) }.filterIsInstance<LinearLayout>()
                    .filter { it.orientation == LinearLayout.HORIZONTAL }
                check(rows.size == 2) { "Native rows not found" }
                rows.forEach { row ->
                    val a = row.getChildAt(0); val b = row.getChildAt(1)
                    if (Rect(a.left, a.top, a.right, a.bottom).intersect(Rect(b.left, b.top, b.right, b.bottom))) problems += "overlap in row"
                    if (b.right > row.width) problems += "button outside row"
                }
                check(problems.isEmpty()) { problems.joinToString() }
                JSONObject().put("font_scale", scale).put("long_text_px", activity.longText.textSize)
                    .put("long_text_lines", activity.longText.lineCount).put("precomputed", activity.longText.text is PrecomputedText)
            }
            evidence("font_scale_2_activity_no_clipping_targets", report)
        } finally { close(activity) }
    }

    private fun rtlActivity() {
        val activity = launch(MatrixActivity::class.java) { rtl = true }
        try {
            val report = main {
                check(activity.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) { "Root not RTL" }
                val rows = (0 until activity.content.childCount).map { activity.content.getChildAt(it) }.filterIsInstance<LinearLayout>()
                    .filter { it.orientation == LinearLayout.HORIZONTAL }
                rows.forEach { row -> check(row.getChildAt(0).left > row.getChildAt(1).left) { "Native row not mirrored" } }
                val holder = activity.recycler.getChildViewHolder(activity.recycler.getChildAt(0)) as MatrixActivity.RowHolder
                check(holder.label.left > holder.action.left) { "List row not mirrored" }
                check(activity.planHost.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                JSONObject().put("rows", rows.size).put("list_label_left", holder.label.left).put("list_action_left", holder.action.left)
            }
            evidence("rtl_activity_mirrors_rows_and_list", report)
        } finally { close(activity) }
    }

    // --- recycler -------------------------------------------------------------------------------
    private fun checkVisibleRows(activity: MatrixActivity) = main {
        check(activity.adapter.itemCount == MatrixActivity.ROWS)
        for (i in 0 until activity.recycler.childCount) {
            val holder = activity.recycler.getChildViewHolder(activity.recycler.getChildAt(i)) as MatrixActivity.RowHolder
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            val id = activity.adapter.getItemId(position)
            check(holder.label.text.toString() == activity.getString(R.string.item_label, id)) { "Stale label at $position" }
            check(holder.action.text.toString() == activity.getString(if (id in activity.toggled) R.string.item_on else R.string.item_off)) { "Stale toggle at $id" }
        }
    }

    /** Diffs are asynchronous: settled means no pending update, no running animation and consistent rows. */
    private fun waitListSettled(activity: MatrixActivity, condition: () -> Boolean) {
        waitFor("list settled", 15_000) {
            condition() && !activity.recycler.hasPendingAdapterUpdates() && !activity.recycler.isAnimating &&
                activity.recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE && runCatching { checkVisibleRowsNow(activity) }.isSuccess
        }
        idle()
    }

    private fun checkVisibleRowsNow(activity: MatrixActivity) {
        check(activity.adapter.itemCount == MatrixActivity.ROWS)
        for (i in 0 until activity.recycler.childCount) {
            val holder = activity.recycler.getChildViewHolder(activity.recycler.getChildAt(i)) as MatrixActivity.RowHolder
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) continue
            val id = activity.adapter.getItemId(position)
            check(holder.action.text.toString() == activity.getString(if (id in activity.toggled) R.string.item_on else R.string.item_off))
        }
    }

    private fun recyclerBurst() {
        val activity = launch(MatrixActivity::class.java)
        try {
            main { check(activity.recycler.itemAnimator is DefaultItemAnimator) }
            val random = java.util.Random(27)
            repeat(30) {
                main {
                    activity.toggle(random.nextInt(MatrixActivity.ROWS).toLong())
                    activity.recycler.smoothScrollBy(0, if (it % 10 < 5) 400 else -300)
                }
                SystemClock.sleep(16)
            }
            waitListSettled(activity) { true }
            checkVisibleRows(activity)
            evidence("recycler_attached_animator_burst_while_scrolling", JSONObject().put("toggled", JSONArray(activity.toggled.toList()))
                .put("full_binds", activity.adapter.fullBinds).put("partial_binds", activity.adapter.partialBinds))
        } finally { close(activity) }
    }

    private fun recyclerDroppedPayload() {
        val activity = launch(MatrixActivity::class.java)
        try {
            main {
                activity.recycler.itemAnimator = object : DefaultItemAnimator() {
                    override fun canReuseUpdatedViewHolder(viewHolder: RecyclerView.ViewHolder, payloads: MutableList<Any>) = false
                }
            }
            val before = main { activity.adapter.fullBinds to activity.adapter.partialBinds }
            main { activity.toggle(2) }
            waitListSettled(activity) { activity.adapter.fullBinds > before.first }
            checkVisibleRows(activity)
            val after = main { activity.adapter.fullBinds to activity.adapter.partialBinds }
            check(after.first > before.first) { "Dropped payload did not cause a full bind" }
            evidence("recycler_dropped_payload_full_bind", JSONObject().put("full_binds_delta", after.first - before.first)
                .put("partial_binds_delta", after.second - before.second))
        } finally { close(activity) }
    }

    private fun recyclerFocus() {
        val activity = launch(MatrixActivity::class.java)
        try {
            sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            main {
                val holder = activity.recycler.findViewHolderForAdapterPosition(5) as MatrixActivity.RowHolder
                check(holder.action.requestFocus())
            }
            main { activity.toggle(5); activity.toggle(4); activity.toggle(6) }
            waitListSettled(activity) { activity.adapter.partialBinds + activity.adapter.fullBinds > 0 }
            val focusedId = main {
                val focused = checkNotNull(activity.currentFocus) { "Focus lost" }
                val holder = activity.recycler.findContainingViewHolder(focused) as MatrixActivity.RowHolder
                check(focused === holder.action)
                activity.adapter.getItemId(holder.bindingAdapterPosition)
            }
            check(focusedId == 5L) { "Focus moved to item $focusedId" }
            checkVisibleRows(activity)
            evidence("recycler_focus_kept_after_diff", JSONObject().put("focused_item", focusedId))
        } finally { close(activity) }
    }

    // --- text -------------------------------------------------------------------------------------
    private class Driver : UiFrameDriver {
        override val owner = "matrix"
        @Volatile var callback: ((FrameBudget) -> Unit)? = null
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            check(this.callback == null); this.callback = callback
            return FrameTicket { this.callback = null }
        }
        fun tick() { val item = checkNotNull(callback) { "No frame requested" }; callback = null; item(FrameBudget(System.nanoTime())) }
    }

    private fun configured(fontScale: Float = 1f, rtl: Boolean = false, locale: String? = null, night: Boolean = false): Context {
        val config = Configuration(targetContext.resources.configuration)
        config.fontScale = fontScale
        locale?.let { config.setLocale(Locale.forLanguageTag(it)) }
        config.setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.US)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return ContextThemeWrapper(targetContext.createConfigurationContext(config), android.R.style.Theme_Material_Light)
    }

    private fun textMatrix() {
        val label = targetContext.getString(R.string.long_text)
        val pool = Executors.newSingleThreadExecutor()
        val combos = listOf(
            JSONObject().put("scale", 1.0).put("rtl", false).put("locale", "en-US").put("break", "simple"),
            JSONObject().put("scale", 1.3).put("rtl", false).put("locale", "en-US").put("break", "high_quality"),
            JSONObject().put("scale", 2.0).put("rtl", false).put("locale", "de-DE").put("break", "high_quality_hyphen"),
            JSONObject().put("scale", 1.0).put("rtl", true).put("locale", "ar").put("break", "simple"),
            JSONObject().put("scale", 2.0).put("rtl", true).put("locale", "ar").put("break", "balanced"),
            JSONObject().put("scale", 1.0).put("rtl", false).put("locale", "en-US").put("break", "simple").put("night", true),
        )
        val results = JSONArray()
        try {
            combos.forEach { combo ->
                val context = configured(combo.getDouble("scale").toFloat(), combo.getBoolean("rtl"), combo.getString("locale"), combo.optBoolean("night"))
                val driver = Driver()
                lateinit var session: UiSession
                lateinit var bound: TextView
                lateinit var plain: TextView
                lateinit var binding: PrecomputedTextBinding<TextView>
                val width = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
                val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                fun TextView.configure() {
                    when (combo.getString("break")) {
                        "high_quality" -> breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                        "high_quality_hyphen" -> { breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY; hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL }
                        "balanced" -> breakStrategy = Layout.BREAK_STRATEGY_BALANCED
                        else -> breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                    }
                    if (combo.getBoolean("rtl")) layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val precompute = main {
                    session = UiSession(CreationEnvironment(context), driver)
                    bound = TextView(context).apply { configure() }
                    plain = TextView(context).apply { configure(); text = label }
                    val ref = session.borrow(bound, ViewKey("text"), ComponentType("text", TextView::class.java))
                    binding = PrecomputedTextBinding(session, ref, pool, { throw AssertionError(it) }, enabled = true)
                    binding.submit(label)
                }
                try {
                    check(precompute == (Build.VERSION.SDK_INT >= 28)) { "Unexpected precompute decision" }
                    waitFor("text frame") { driver.callback != null }
                    val row = main {
                        driver.tick()
                        listOf(bound, plain).forEach { it.measure(width, height); it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
                        check(bound.text.toString() == label)
                        check((bound.text is PrecomputedText) == (Build.VERSION.SDK_INT >= 28)) { "Precomputed result not applied" }
                        val a = bound.layout; val b = plain.layout
                        check(a.lineCount == b.lineCount) { "Line count ${a.lineCount} vs ${b.lineCount} for $combo" }
                        for (line in 0 until a.lineCount) {
                            check(a.getLineEnd(line) == b.getLineEnd(line)) { "Line break $line differs for $combo" }
                            check(kotlin.math.abs(a.getLineWidth(line) - b.getLineWidth(line)) < 0.5f) { "Line width $line differs for $combo" }
                        }
                        val node = bound.createAccessibilityNodeInfo()
                        check(node.text?.toString() == label) { "Accessibility text differs" }
                        JSONObject(combo.toString()).put("lines", a.lineCount).put("precomputed", bound.text is PrecomputedText)
                    }
                    results.put(row)
                } finally { main { binding.close(); session.close() } }
            }
        } finally { pool.shutdownNow() }
        evidence("text_precomputed_equals_plain_layout_matrix", JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("fallback_path", Build.VERSION.SDK_INT < 28).put("cases", results))
    }

    // --- layouts ----------------------------------------------------------------------------------
    private fun layoutsMatrix() {
        val results = JSONArray()
        for (scale in listOf(0.85f, 1.0f, 1.3f, 2.0f)) for (rtl in listOf(false, true)) {
            val context = configured(scale, rtl)
            val row = main {
                val session = UiSession(CreationEnvironment(context))
                try {
                    val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; if (rtl) layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    val actual = LinearScope(session, host).nativeRow(ViewKey("row"), linearParams()) {
                        text(ViewKey("label"), linearParams(0, WrapContent) { weight = 1f; marginEnd = 12 }) { text = "Wi-Fi" }
                        text(ViewKey("big"), linearParams(WrapContent, WrapContent)) { text = "Big"; textSize = 24f }
                        button(ViewKey("a"), linearParams(WrapContent, WrapContent)) { text = "OK" }
                        button(ViewKey("b"), linearParams(WrapContent, WrapContent)) { text = "More" }
                    }.get()
                    val reference = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(context).apply { text = "Wi-Fi" }, linearParams(0, WrapContent) { weight = 1f; marginEnd = 12 })
                        addView(TextView(context).apply { text = "Big"; textSize = 24f }, linearParams(WrapContent, WrapContent))
                        addView(Button(context).apply { text = "OK" }, linearParams(WrapContent, WrapContent))
                        addView(Button(context).apply { text = "More" }, linearParams(WrapContent, WrapContent))
                    }
                    val referenceHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; if (rtl) layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(reference, linearParams()) }
                    val width = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
                    val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    listOf(host, referenceHost).forEach { it.measure(width, height); it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
                    fun geometry(group: LinearLayout) = listOf(group.measuredWidth, group.measuredHeight, group.baseline) +
                        (0 until group.childCount).flatMap { i -> group.getChildAt(i).let { listOf(it.left, it.top, it.right, it.bottom, it.baseline) } }
                    check(geometry(actual) == geometry(reference)) { "Geometry differs at $scale rtl=$rtl" }
                    val label = actual.getChildAt(0); val big = actual.getChildAt(1)
                    check(label.top + label.baseline == big.top + big.baseline) { "Baselines not aligned at $scale" }
                    val minimum = 48 * context.resources.displayMetrics.density - 1
                    (2..3).forEach { check(actual.getChildAt(it).height >= minimum) { "Button below 48dp at $scale" } }
                    if (rtl) check(actual.getChildAt(0).left > actual.getChildAt(3).left) { "Not mirrored" }
                    // Detached rows have no focus root; compare the focusable order keyboard traversal starts from.
                    fun focusables(group: LinearLayout) = ArrayList<View>().also { group.addFocusables(it, View.FOCUS_FORWARD, View.FOCUSABLES_ALL) }
                        .map { group.indexOfChild(it) }
                    check(focusables(actual) == focusables(reference) && focusables(actual) == listOf(2, 3)) { "Focus order differs: ${focusables(actual)}" }
                    JSONObject().put("scale", scale.toDouble()).put("rtl", rtl).put("height", actual.measuredHeight)
                        .put("button_height", actual.getChildAt(2).height).put("baseline", actual.baseline)
                } finally { session.close() }
            }
            results.put(row)
        }
        evidence("layouts_font_scale_rtl_geometry_targets_focus_baseline", JSONObject().put("cases", results)
            .put("target_sdk", targetContext.applicationInfo.targetSdkVersion).put("process", android.os.Process.myPid())
            .put("device_api", Build.VERSION.SDK_INT))
    }

    // --- plan ------------------------------------------------------------------------------------
    private fun planMatrix() {
        val results = JSONArray()
        for (scale in listOf(1.0f, 2.0f)) for (rtl in listOf(false, true)) {
            val context = configured(scale, rtl)
            val row = main {
                val planSession = UiSession(CreationEnvironment(context)); val directSession = UiSession(CreationEnvironment(context))
                try {
                    fun host() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; if (rtl) layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    val planned = host(); val direct = host()
                    val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, Framework.textFactory)))
                    val component = PlanComponent(Framework.text, "linear", LinearLayout::class.java, LinearPolicy, { w, h -> linearParams(w, h) })
                    val labels = listOf("First plan row", "A second, much longer plan row that wraps when the font is large")
                    val mounted = UiPlan(rootKind = "linear", nodes = labels.indices.map { PlanNode("n$it", Framework.text.id) })
                        .validate().resolve(registry, listOf(component)).bind(planSession, planned, "linear").mount()
                    labels.forEachIndexed { i, text -> mounted.ref(listOf("n$i"), Framework.text).get().text = text }
                    val scope = LinearScope(directSession, direct)
                    labels.forEachIndexed { i, text -> scope.text(ViewKey("n$i"), linearParams()) { this.text = text } }
                    val width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
                    val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    listOf(planned, direct).forEach { it.measure(width, height); it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
                    fun geometry(group: LinearLayout) = (0 until group.childCount).flatMap { i ->
                        group.getChildAt(i).let { listOf(it.left, it.top, it.right, it.bottom, (it as TextView).lineCount) }
                    }
                    check(geometry(planned) == geometry(direct)) { "Plan geometry differs at $scale rtl=$rtl" }
                    // Detached views have no accessibility connection; compare what each node would expose, in child order.
                    fun order(group: LinearLayout) = (0 until group.childCount).map { group.getChildAt(it) }.map {
                        Triple(it.createAccessibilityNodeInfo().text?.toString(), it.importantForAccessibility, it.isFocusable)
                    }
                    check(order(planned) == order(direct) && order(planned).map { it.first } == labels) { "Accessibility order differs: ${order(planned)}" }
                    JSONObject().put("scale", scale.toDouble()).put("rtl", rtl).put("height", planned.measuredHeight)
                } finally { planSession.close(); directSession.close() }
            }
            results.put(row)
        }
        evidence("plan_rtl_font_scale_geometry_and_accessibility_order", JSONObject().put("cases", results))
    }

    // --- prewarm ------------------------------------------------------------------------------------
    private fun released(activity: MatrixActivity): Boolean {
        repeat(20) {
            Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(50)
            if (main { activity.stagedPage?.get() } == null) return true
        }
        return false
    }

    private fun memory(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } + Debug.getNativeHeapAllocatedSize()

    private fun prewarmLowMemory() {
        val activity = launch(MatrixActivity::class.java) { prewarmPaused = true }
        try {
            val before = memory()
            uiAutomation.executeShellCommand("am send-trim-memory ${targetContext.packageName} RUNNING_LOW").close()
            var delivered = true
            try { waitFor("low memory stop", 8_000) { activity.prewarmStop == PrewarmStop.LOW_MEMORY } }
            catch (timeout: IllegalStateException) { delivered = false }
            check(delivered) { "RUNNING_LOW trim was not delivered (stop=${main { activity.prewarmStop }}, api=${Build.VERSION.SDK_INT})" }
            check(released(activity)) { "Staged page retained after low memory" }
            main { activity.openPage() }
            idle()
            check(main { activity.pageBuiltLazily && (activity.pageHost.getChildAt(0) as ViewGroup).let { (it.getChildAt(0) as ViewGroup).childCount } == MatrixActivity.PAGE_ROWS })
            evidence("prewarm_low_memory_trim_releases_staged_page", JSONObject().put("stop", "LOW_MEMORY")
                .put("heap_before", before).put("heap_after", memory()))
        } finally { close(activity) }
    }

    private fun prewarmResourceChange() {
        val activity = launch(MatrixConfigActivity::class.java) { prewarmPaused = true }
        try {
            val instance = activity
            main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("in-place configuration change", 10_000) { activity.prewarmStop == PrewarmStop.RESOURCE_CHANGED }
            check(main { activity === instance && !activity.isDestroyed }) { "Activity was recreated instead of receiving the change" }
            check(released(activity)) { "Staged page retained after resource change" }
            waitFor("text rebound") { Build.VERSION.SDK_INT < 28 || activity.longText.text is PrecomputedText }
            evidence("prewarm_rotation_resource_change_releases_staged_page", JSONObject().put("stop", "RESOURCE_CHANGED")
                .put("orientation", main { activity.resources.configuration.orientation }))
        } finally { close(activity) }
    }

    private fun prewarmDetachAndFallback() {
        val detached = launch(MatrixActivity::class.java) { prewarmPaused = true }
        val heapBefore = memory()
        try {
            uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            // onStop follows the launcher becoming idle, or the platform's 10 s idle timeout on slow devices.
            waitFor("detach stop", 30_000) { detached.prewarmStop == PrewarmStop.DETACHED }
            check(released(detached)) { "Staged page retained after detach" }
        } finally { close(detached) }
        val fallback = launch(MatrixActivity::class.java) { prewarmPaused = true }
        try {
            main { fallback.openPage() }
            check(main { fallback.prewarmStop == PrewarmStop.CANCELLED && fallback.pageBuiltLazily })
        } finally { close(fallback) }
        // A second instance of the task root is only created once the previous one is gone.
        val warmed = launch(MatrixActivity::class.java)
        try {
            waitFor("page prewarmed or discarded over budget", 10_000) { warmed.pageReady || warmed.prewarmStop == PrewarmStop.OVER_BUDGET }
            val overBudget = main { warmed.prewarmStop == PrewarmStop.OVER_BUDGET }
            if (overBudget) check(released(warmed)) { "Over-budget staging retained" }
            val peak = memory()
            main { warmed.openPage() }
            check(main { warmed.pageBuiltLazily == overBudget && warmed.pageHost.childCount == 1 &&
                ((warmed.pageHost.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup).childCount == MatrixActivity.PAGE_ROWS })
            evidence("prewarm_home_detach_and_unfinished_page_fallback", JSONObject().put("detach_stop", "DETACHED")
                .put("fallback_stop", "CANCELLED").put("warm_path", if (overBudget) "OVER_BUDGET then built on open" else "prewarmed page used")
                .put("heap_before", heapBefore).put("heap_with_page", peak))
        } finally { close(warmed) }
    }
}
