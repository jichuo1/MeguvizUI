package io.github.jichuo1.meguviz.ui.layouts

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.WrapContent
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.linearParams

/** Runs on-device against the platform LinearLayout rather than simulated host widgets. */
class RowInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "ltr_normal_text" to { compareNativeRow(View.LAYOUT_DIRECTION_LTR, 14f, "Title") },
            "rtl_long_large_text" to { compareNativeRow(View.LAYOUT_DIRECTION_RTL, 24f, "Long title that wraps in the available space") },
            "bound_failure_releases_children" to {
                val root = FrameLayout(targetContext)
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    var rejected = false
                    try {
                        FrameScope(session, root).nativeRow(ViewKey("row"), frameParams(), NativeRowConstraints(1)) {
                            text(ViewKey("first"), linearParams())
                            text(ViewKey("second"), linearParams())
                        }
                    } catch (_: IllegalArgumentException) { rejected = true }
                    check(rejected && root.childCount == 0)
                    FrameScope(session, root).nativeRow(ViewKey("row"), frameParams()) {}
                    check(root.childCount == 1)
                } finally { session.close() }
            },
            "vertical_configuration_is_rejected" to {
                val root = FrameLayout(targetContext)
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    var rejected = false
                    try {
                        FrameScope(session, root).nativeRow(ViewKey("row"), frameParams(), configure = { orientation = LinearLayout.VERTICAL }) {}
                    } catch (_: IllegalArgumentException) { rejected = true }
                    check(rejected && root.childCount == 0)
                } finally { session.close() }
            },
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", RowInstrumentation::class.java.name)
                putString("test", name)
                putInt("numtests", cases.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            runOnMainSync { try { body() } catch (error: Throwable) { failure = error } }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "Native rows: ${cases.size} tests, $failures failures\n")
        })
    }

    private fun compareNativeRow(direction: Int, size: Float, label: String) {
        val session = UiSession(CreationEnvironment(targetContext))
        try {
            val root = FrameLayout(targetContext)
            var clicks = 0
            val actual = FrameScope(session, root).nativeRow(ViewKey("row"), frameParams(), configure = {
                layoutDirection = direction
                minimumHeight = 64
                setPadding(9, 3, 7, 5)
            }) {
                text(ViewKey("text"), linearParams(0, WrapContent) { weight = 1f; marginStart = 7; marginEnd = 11 }) {
                    text = label; textSize = size
                }
                button(ViewKey("button"), linearParams(WrapContent, WrapContent)) {
                    text = "OK"; setOnClickListener { clicks++ }
                }
            }.get()
            val baseline = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = direction
                minimumHeight = 64
                setPadding(9, 3, 7, 5)
                addView(TextView(targetContext).apply { text = label; textSize = size },
                    linearParams(0, WrapContent) { weight = 1f; marginStart = 7; marginEnd = 11 })
                addView(Button(targetContext).apply { text = "OK" }, linearParams(WrapContent, WrapContent))
            }
            val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST)
            listOf(actual, baseline).forEach { it.measure(width, height); it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
            fun geometry(row: LinearLayout): List<Int> = listOf(row.measuredWidth, row.measuredHeight, row.baseline) +
                (0 until row.childCount).flatMap { i -> row.getChildAt(i).let { listOf(it.left, it.top, it.right, it.bottom, it.baseline, if (it.isFocusable) 1 else 0, it.importantForAccessibility) } }
            check(geometry(actual) == geometry(baseline))
            check(actual.getChildAt(1).performClick() && clicks == 1)
        } finally { session.close() }
    }
}
