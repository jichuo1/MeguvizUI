package io.github.jichuo1.meguviz.samples.plan

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.plan.MountedUiPlan
import io.github.jichuo1.meguviz.ui.plan.PlanComponent
import io.github.jichuo1.meguviz.ui.plan.PlanNode
import io.github.jichuo1.meguviz.ui.plan.UiPlan
import io.github.jichuo1.meguviz.ui.plan.resolve
import io.github.jichuo1.meguviz.ui.plan.validate
import io.github.jichuo1.meguviz.ui.widgets.FramePolicy
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.frameParams

class MainActivity : Activity() {
    private lateinit var session: UiSession
    private lateinit var mounted: MountedUiPlan
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this); setContentView(root)
        session = UiSession(CreationEnvironment(this))
        val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, Framework.textFactory)))
        val component = PlanComponent(Framework.text, "frame", FrameLayout::class.java, FramePolicy, { w, h -> frameParams(w, h) })
        mounted = UiPlan(rootKind = "frame", nodes = listOf(PlanNode("title", Framework.text.id))).validate()
            .resolve(registry, listOf(component)).bind(session, root, "frame").mount()
        mounted.ref(listOf("title"), Framework.text).get().setText(R.string.ready)
    }
    override fun onDestroy() { mounted.close(); session.close(); super.onDestroy() }
}
