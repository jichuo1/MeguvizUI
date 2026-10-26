package io.github.jichuo1.meguviz.samples.standalone

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.*
import io.github.jichuo1.meguviz.ui.scheduler.*
import io.github.jichuo1.meguviz.ui.state.*
import io.github.jichuo1.meguviz.ui.widgets.*
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Real Android View tests; host tests cannot substitute for running this instrumentation. */
class ContractInstrumentation : Instrumentation() {
    private class Driver : UiFrameDriver {
        override val owner = "instrumentation.manual"
        var callback: ((FrameBudget) -> Unit)? = null
        var requests = 0
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            check(this.callback == null); this.callback = callback; requests++
            return FrameTicket { this.callback = null }
        }
        fun tick() { val next = callback; callback = null; next?.invoke(FrameBudget(System.nanoTime())) }
    }
    private fun fails(block: () -> Unit) { var failed = false; try { block() } catch (_: IllegalStateException) { failed = true } catch (_: IllegalArgumentException) { failed = true }; check(failed) }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "typed_parent_weight_rtl" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val parent = LinearLayout(targetContext).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    val ref = LinearScope(session, parent).text(ViewKey("title"), linearParams(0, 40) { weight = 1f; marginStart = 7 }) { text = "Label" }
                    val params = ref.get().layoutParams as LinearLayout.LayoutParams
                    check(params.weight == 1f && params.marginStart == 7)
                    parent.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, 200, 80)
                    check(ref.get().measuredWidth == 193)
                } finally { session.close() }
            },
            "duplicate_key_before_constructor" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    val root = FrameLayout(targetContext); val scope = FrameScope(session, root)
                    scope.text(ViewKey("x"), frameParams())
                    var constructions = 0
                    fails { scope.view(ViewKey("x"), Framework.text, frameParams(), ViewFactory { constructions++; TextView(it.context) }) }
                    check(constructions == 0 && root.childCount == 1)
                } finally { session.close() }
            },
            "borrowed_view_and_listener_untouched" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                val root = FrameLayout(targetContext); val text = TextView(targetContext); root.addView(text)
                var clicks = 0; text.setOnClickListener { clicks++ }
                val ref = session.borrow(text, ViewKey("borrowed"), Framework.text)
                session.close(); text.performClick()
                check(root.childCount == 1 && clicks == 1); fails { ref.get() }
            },
            "transfer_never_steals_external_child" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    val first = FrameLayout(targetContext); val second = FrameLayout(targetContext); val text = TextView(targetContext); first.addView(text)
                    fails { session.transfer(text, second, FramePolicy, frameParams(), ViewKey("x"), Framework.text) }
                    check(text.parent === first && second.childCount == 0)
                } finally { session.close() }
            },
            "reject_parented_factory_without_removing_external_child" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                val root = FrameLayout(targetContext)
                val external = TextView(targetContext); root.addView(external)
                try {
                    fails { FrameScope(session, root).view(ViewKey("external"), Framework.text, frameParams(), ViewFactory { external }) }
                    check(root.childCount == 1 && external.parent === root)
                } finally { session.close() }
                check(root.childCount == 1 && external.parent === root)
            },
            "constructor_failure_single_attempt" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    var attempts = 0; val root = FrameLayout(targetContext)
                    fails { FrameScope(session, root).view(ViewKey("bad"), Framework.text, frameParams(), ViewFactory { attempts++; error("constructor") }) }
                    check(attempts == 1 && root.childCount == 0)
                    FrameScope(session, root).text(ViewKey("bad"), frameParams())
                } finally { session.close() }
            },
            "shared_property_reads_external_write" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    val binding = session.bind(ref, Properties.plainText())
                    binding.submit("wanted"); driver.tick(); ref.get().text = "external"
                    binding.submit("wanted"); driver.tick()
                    check(ref.get().text.toString() == "wanted" && binding.writes == 2L)
                } finally { session.close() }
            },
            "rich_spans_are_not_plain_string_equal" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    val binding = session.bind(ref, Properties.richText())
                    val first = SpannableString("same").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    val second = SpannableString("same").apply { setSpan(StyleSpan(Typeface.ITALIC), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    binding.submit(first); binding.submit(second); driver.tick()
                    val spans = (ref.get().text as Spanned).getSpans(0, 4, StyleSpan::class.java)
                    check(binding.writes == 2L && spans.single().style == Typeface.ITALIC)
                } finally { session.close() }
            },
            "switch_rejection_final_authority" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val type = ComponentType("test.checkbox", CheckBox::class.java)
                    val ref = FrameScope(session, FrameLayout(targetContext)).view(ViewKey("switch"), type, frameParams(), ViewFactory { CheckBox(it.context) })
                    ref.get().setOnCheckedChangeListener { button, checked -> if (checked) button.isChecked = false }
                    val binding = session.bind(ref, PropertySpec<CheckBox, Boolean>("checked", read = { it.isChecked }, write = { view, value -> view.isChecked = value }))
                    binding.submit(true); binding.submit(true); driver.tick()
                    check(!ref.get().isChecked && binding.writes == 2L)
                } finally { session.close() }
            },
            "plain_label_does_not_skip_existing_span" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    ref.get().text = SpannableString("same").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    val binding = session.bind(ref, Properties.plainText()); binding.submit("same"); driver.tick()
                    check(binding.writes == 1L && ref.get().text !is Spanned)
                } finally { session.close() }
            },
            "rebind_rejects_and_releases_old_result" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    val binding = session.bind(ref, Properties.plainText()); binding.submit("old")
                    val old = ref.token(); session.rebind(ref)
                    check(!old.isCurrent() && session.scheduler.pendingStateCount == 0)
                    driver.tick(); check(ref.get().text.isEmpty())
                } finally { session.close() }
            },
            "detach_reattach_keeps_owned_listener" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    var clicks = 0; session.click(ref) { clicks++ }; val old = ref.token()
                    session.detach(); ref.get().performClick(); session.reattach(); ref.get().performClick()
                    check(clicks == 1 && !old.isCurrent())
                } finally { session.close() }
            },
            "theme_invalidates_same_context" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    val color = session.bind(ref, Properties.textColor()); color.submit(0xff123456.toInt())
                    val token = ref.token(resources = true); session.invalidateTheme(); check(!token.isCurrent())
                    driver.tick(); check(color.writes == 0L)
                } finally { session.close() }
            },
            "single_driver_and_latest_300" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    val binding = session.bind(ref, Properties.plainText()); repeat(300) { binding.submit("$it") }
                    check(driver.requests == 1); driver.tick(); check(ref.get().text.toString() == "299" && binding.writes == 1L)
                } finally { session.close() }
            },
            "close_during_attach_cannot_register" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                val root = object : FrameLayout(targetContext) { override fun onViewAdded(child: View) { super.onViewAdded(child); session.close() } }
                fails { FrameScope(session, root).text(ViewKey("late"), frameParams()) }
                check(root.childCount == 0 && session.state == SessionState.DISPOSED)
            },
            "ownership_reservation_rejects_ui_writer" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    val ref = FrameScope(session, FrameLayout(targetContext)).text(ViewKey("text"), frameParams())
                    session.reserveProperties(ref, setOf("background"))
                    fails { session.bind(ref, PropertySpec<TextView, Int>("background", write = { view, color -> view.setBackgroundColor(color) })) }
                } finally { session.close() }
            },
            "restoration_ids_bundle_roundtrip" to {
                val session = UiSession(CreationEnvironment(targetContext), Driver())
                try {
                    val root = FrameLayout(targetContext); val ref = FrameScope(session, root).edit(ViewKey("input"), frameParams()) { setText("model") }
                    val restore = ViewRestoration(session); restore.register("editor/input", ref, root)
                    ref.get().setSelection(2)
                    val copy = RestorationSnapshot.fromBundle(restore.snapshot().toBundle())
                    check(copy.ids.values.single() == ref.get().id && copy.presentation.values.single().selectionStart == 2)
                } finally { session.close() }
            },
            "dispose_releases_owned_tree_and_rejects_commits" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                val root = FrameLayout(targetContext); val ref = FrameScope(session, root).text(ViewKey("text"), frameParams())
                session.bind(ref, Properties.plainText()).submit("queued"); session.close(); session.close(); driver.tick()
                check(root.childCount == 0 && session.scheduler.pendingStateCount == 0)
                fails { ref.get() }
            },
            "resolved_provider_10000_updates_no_rescan" to {
                val driver = Driver(); val session = UiSession(CreationEnvironment(targetContext), driver)
                try {
                    var constructions = 0
                    val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, ViewFactory { env -> constructions++; TextView(env.context) })))
                    val resolved = registry.resolve(Framework.text)
                    val ref = FrameScope(session, FrameLayout(targetContext)).view(ViewKey("resolved"), Framework.text, frameParams(), resolved)
                    val binding = session.bind(ref, Properties.plainText())
                    repeat(10000) { binding.submit(it.toString()); driver.tick() }
                    check(constructions == 1 && registry.candidateChecks == 1L && binding.writes == 10000L)
                } finally { session.close() }
            },
        )
        val results = JSONArray()
        var failures = 0
        val total = cases.size + 1
        cases.entries.forEachIndexed { index, (name, body) ->
            sendStatus(1, Bundle().apply { putString("class", ContractInstrumentation::class.java.name); putString("test", name); putInt("numtests", total); putInt("current", index + 1) })
            var error: Throwable? = null
            runOnMainSync { try { body() } catch (failure: Throwable) { error = failure } }
            val result = JSONObject().put("name", name).put("passed", error == null)
            val status = Bundle().apply { putString("class", ContractInstrumentation::class.java.name); putString("test", name); putInt("numtests", total); putInt("current", index + 1) }
            error?.let { failures++; result.put("error", it.stackTraceToString()); status.putString("stack", it.stackTraceToString()) }
            results.put(result); sendStatus(if (error == null) 0 else -2, status)
        }
        val choreographerName = "default_choreographer_main_thread_tick"
        sendStatus(1, Bundle().apply { putString("class", ContractInstrumentation::class.java.name); putString("test", choreographerName); putInt("numtests", total); putInt("current", total) })
        val latch = CountDownLatch(1)
        val asynchronousFailure = AtomicReference<Throwable?>()
        var platformSession: UiSession? = null
        runOnMainSync {
            try {
                platformSession = UiSession(CreationEnvironment(targetContext))
                platformSession!!.scheduler.submitCommand {
                    try { check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) }
                    catch (failure: Throwable) { asynchronousFailure.set(failure) }
                    finally { latch.countDown() }
                }
            } catch (failure: Throwable) { asynchronousFailure.set(failure); latch.countDown() }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) asynchronousFailure.compareAndSet(null, AssertionError("No actual Choreographer tick within 5 seconds"))
        runOnMainSync { try { platformSession?.close() } catch (failure: Throwable) { asynchronousFailure.compareAndSet(null, failure) } }
        val asynchronousError = asynchronousFailure.get()
        if (asynchronousError != null) failures++
        results.put(JSONObject().put("name", choreographerName).put("passed", asynchronousError == null).apply { asynchronousError?.let { put("error", it.stackTraceToString()) } })
        sendStatus(if (asynchronousError == null) 0 else -2, Bundle().apply {
            putString("class", ContractInstrumentation::class.java.name)
            putString("test", choreographerName); putInt("numtests", total); putInt("current", total)
            asynchronousError?.let { putString("stack", it.stackTraceToString()) }
        })
        val receipt = JSONObject().put("tests", total).put("failures", failures).put("api", android.os.Build.VERSION.SDK_INT).put("cases", results)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("meguviz_results", receipt.toString()); putString("stream", "MeguvizUI: $total tests, $failures failures\n")
        })
    }
}
