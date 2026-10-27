package io.github.jichuo1.meguviz.samples.plan

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.plan.PlanComponent
import io.github.jichuo1.meguviz.ui.plan.PlanNode
import io.github.jichuo1.meguviz.ui.plan.UiPlan
import io.github.jichuo1.meguviz.ui.plan.resolve
import io.github.jichuo1.meguviz.ui.plan.validate
import io.github.jichuo1.meguviz.ui.widgets.FramePolicy
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.frameParams

class PlanInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "direct_and_plan_same_native_geometry" to {
                val planSession = UiSession(CreationEnvironment(targetContext)); val directSession = UiSession(CreationEnvironment(targetContext))
                try {
                    val plannedRoot = FrameLayout(targetContext); val directRoot = FrameLayout(targetContext)
                    val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, Framework.textFactory)))
                    val component = PlanComponent(Framework.text, "frame", FrameLayout::class.java, FramePolicy, { w, h -> frameParams(w, h) })
                    val plan = UiPlan(rootKind = "frame", nodes = listOf(PlanNode("title", Framework.text.id, width = 100, height = 40)))
                    val resolved = plan.validate().resolve(registry, listOf(component))
                    val checks = registry.candidateChecks
                    val mounted = resolved.bind(planSession, plannedRoot, "frame").mount()
                    val direct = FrameScope(directSession, directRoot).text(ViewKey("title"), frameParams(100, 40))
                    val planned = mounted.ref(listOf("title"), Framework.text)
                    val width = View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
                    val height = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
                    for (root in listOf(plannedRoot, directRoot)) { root.measure(width, height); root.layout(0, 0, 300, 200) }
                    check(planned.get().javaClass == direct.get().javaClass)
                    check(planned.get().width == direct.get().width && planned.get().height == direct.get().height)
                    check(registry.candidateChecks == checks)
                    mounted.close(); check(plannedRoot.childCount == 0)
                    check(runCatching { planned.get() }.isFailure)
                } finally { planSession.close(); directSession.close() }
            },
            "failed_factory_releases_already_mounted_nodes" to {
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    val root = FrameLayout(targetContext); var calls = 0
                    val factory = ViewFactory { env: CreationEnvironment -> if (++calls == 2) error("expected") else Framework.textFactory.create(env) }
                    val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, factory)))
                    val component = PlanComponent(Framework.text, "frame", FrameLayout::class.java, FramePolicy, { w, h -> frameParams(w, h) })
                    val nodes = listOf(PlanNode("first", Framework.text.id), PlanNode("second", Framework.text.id))
                    val bound = UiPlan(rootKind = "frame", nodes = nodes).validate().resolve(registry, listOf(component)).bind(session, root, "frame")
                    check(runCatching { bound.mount() }.isFailure && calls == 2 && root.childCount == 0)
                    check(runCatching { bound.mount() }.isFailure)
                } finally { session.close() }
            },
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, test) ->
            sendStatus(1, Bundle().apply { putString("class", PlanInstrumentation::class.java.name); putString("test", name); putInt("numtests", cases.size); putInt("current", index + 1) })
            var error: Throwable? = null
            try { runOnMainSync { try { test() } catch (failure: Throwable) { error = failure } } }
            catch (failure: Throwable) { error = failure }
            val status = Bundle().apply { putString("class", PlanInstrumentation::class.java.name); putString("test", name); putInt("numtests", cases.size); putInt("current", index + 1) }
            error?.let { failures++; status.putString("stack", it.stackTraceToString()) }
            sendStatus(if (error == null) 0 else -2, status)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "UiPlan: ${cases.size} tests, $failures failures\n") })
    }
}
