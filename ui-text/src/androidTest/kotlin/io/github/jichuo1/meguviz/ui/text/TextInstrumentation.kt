package io.github.jichuo1.meguviz.ui.text

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.PrecomputedText
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

class TextInstrumentation : Instrumentation() {
    private class Driver : UiFrameDriver {
        override val owner = "text-instrumentation"
        var callback: ((FrameBudget) -> Unit)? = null
        override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
            check(this.callback == null); this.callback = callback
            return FrameTicket { this.callback = null }
        }
        fun tick() { val item = callback; callback = null; item?.invoke(FrameBudget(System.nanoTime())) }
    }
    private class Queue : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() = checkNotNull(tasks.poll()) { "Expected background work" }.run()
        fun empty() = tasks.isEmpty()
    }
    private class Fixture(val session: UiSession, val driver: Driver, val background: Queue, val view: TextView,
                          val ref: ViewRef<TextView>, val binding: PrecomputedTextBinding<TextView>)

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun fixture(view: () -> TextView = { TextView(targetContext) }): Fixture {
        lateinit var created: Fixture
        main {
            val driver = Driver(); val background = Queue()
            val session = UiSession(CreationEnvironment(targetContext), driver)
            val text = view()
            val ref = session.borrow(text, ViewKey("text"), ComponentType("text", TextView::class.java))
            val binding = PrecomputedTextBinding(session, ref, background, { throw AssertionError(it) }, enabled = true, minimumLength = 4)
            created = Fixture(session, driver, background, text, ref, binding)
        }
        return created
    }

    /** Runs queued background work and its main-thread completion until nothing is pending. */
    private fun drain(fixture: Fixture) {
        var guard = 0
        while (!fixture.background.empty()) { check(guard++ < 10) { "Unbounded recomputation" }; fixture.background.run(); waitForIdleSync() }
    }

    private fun applied(fixture: Fixture, expected: String, precomputed: Boolean) = main {
        fixture.driver.tick()
        check(fixture.view.text.toString() == expected) { "Text '${fixture.view.text}' instead of '$expected'" }
        check((fixture.view.text is PrecomputedText) == precomputed) { "Precomputed=${fixture.view.text is PrecomputedText}, expected $precomputed" }
    }

    private fun precomputationAndInvalidation() {
        val f = fixture()
        try {
            main {
                val usesPrecompute = f.binding.submit("first long label")
                check(usesPrecompute == (Build.VERSION.SDK_INT >= 28))
            }
            drain(f); applied(f, "first long label", Build.VERSION.SDK_INT >= 28)
            main {
                val rich = SpannableString("styled")
                rich.setSpan(StyleSpan(Typeface.BOLD), 0, rich.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                check(!f.binding.submit(rich)); f.driver.tick()
                check((f.view.text as Spanned).getSpans(0, f.view.text.length, StyleSpan::class.java).size == 1)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                main { f.binding.submit("stale after rebind"); f.session.rebind(f.ref) }
                drain(f)
                main { f.driver.tick(); check(f.view.text.toString() == "styled") }
            }
        } finally { main { f.session.close() } }
    }

    /** A result computed with outdated parameters is never applied; the latest text is recomputed. */
    private fun parameterChangeRecomputesLatest() {
        if (Build.VERSION.SDK_INT < 28) return
        val f = fixture()
        try {
            main { f.binding.submit("after font change"); f.view.textSize = 37f }
            f.background.run(); waitForIdleSync()
            main { f.driver.tick(); check(f.view.text.toString() != "after font change") { "Stale-parameter result was applied" } }
            drain(f); applied(f, "after font change", true)
            if (Build.VERSION.SDK_INT >= 33) {
                main { f.binding.submit("after line break change"); f.view.lineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_STRICT }
                drain(f); applied(f, "after line break change", true)
            }
            // Parameters that keep changing exhaust the bounded retries and fall back to the plain setter.
            main { f.binding.submit("unstable parameters"); f.view.textSize = 20f }
            repeat(3) { step ->
                f.background.run(); waitForIdleSync()
                if (step < 2) main { f.view.textSize = 21f + step }
            }
            check(f.background.empty()) { "Retries were not bounded" }
            applied(f, "unstable parameters", false)
        } finally { main { f.session.close() } }
    }

    /** Layout direction resolves when the view joins an RTL parent, after submission. */
    private fun directionResolvedAfterSubmit() {
        if (Build.VERSION.SDK_INT < 28) return
        val parent = LinearLayout(targetContext).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val f = fixture()
        try {
            val label = "مرحبا بالعالم long mixed direction label"
            main { f.binding.submit(label) }
            main {
                parent.addView(f.view)
                parent.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                check(f.view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            }
            drain(f); applied(f, label, true)
        } finally { main { parent.removeAllViews(); f.session.close() } }
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val cases = linkedMapOf(
            "text_precomputation_and_invalidation" to ::precomputationAndInvalidation,
            "text_parameter_change_recomputes_latest" to ::parameterChangeRecomputesLatest,
            "text_direction_resolved_after_submit" to ::directionResolvedAfterSubmit,
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", TextInstrumentation::class.java.name); putString("test", name)
                putInt("numtests", cases.size); putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            try { body() } catch (error: Throwable) { failure = error }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "Text: ${cases.size} tests, $failures failures\n") })
    }
}
