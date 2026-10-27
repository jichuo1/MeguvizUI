package io.github.jichuo1.meguviz.samples.p2consumer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.layouts.nativeRow
import io.github.jichuo1.meguviz.ui.plan.MountedUiPlan
import io.github.jichuo1.meguviz.ui.plan.PlanComponent
import io.github.jichuo1.meguviz.ui.plan.PlanNode
import io.github.jichuo1.meguviz.ui.plan.UiPlan
import io.github.jichuo1.meguviz.ui.plan.resolve
import io.github.jichuo1.meguviz.ui.plan.validate
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmWork
import io.github.jichuo1.meguviz.ui.prewarm.UiPrewarmer
import io.github.jichuo1.meguviz.ui.recycler.UiListAdapter
import io.github.jichuo1.meguviz.ui.text.PrecomputedTextBinding
import io.github.jichuo1.meguviz.ui.widgets.FramePolicy
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import java.util.concurrent.Executors

@GenerateUiFactory(id = "independent.generated.label")
class GeneratedLabel(context: Context) : TextView(context)

private data class Item(val id: Long, val label: String)
private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var session: UiSession
    private lateinit var mounted: MountedUiPlan
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: UiListAdapter<Item, Holder>

    @Suppress("DEPRECATION") // Public insets and system bar flags support API 27.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (resources.displayMetrics.density * 16).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            insets
        }
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        session = UiSession(CreationEnvironment(this))
        val registry = RegistrySnapshot.install(listOf(
            GeneratedLabelUiGenerated.registration(),
            FactoryRegistration(ExtensionDescriptor("native-text", "1"), Framework.text, Framework.textFactory),
        ))
        LinearScope(session, root).nativeRow(ViewKey("header"), linearParams()) {
            val label: GeneratedLabelViewRef = view(ViewKey("generated"), GeneratedLabelUiGenerated.type,
                linearParams(), registry.resolve(GeneratedLabelUiGenerated.type))
            PrecomputedTextBinding(session, label, executor, { throw IllegalStateException(it) }, enabled = true,
                minimumLength = 4).submit(getString(R.string.generated_label))
        }
        val planRoot = FrameLayout(this)
        root.addView(planRoot)
        val component = PlanComponent(Framework.text, "frame", FrameLayout::class.java, FramePolicy,
            { width, height -> frameParams(width, height) })
        mounted = UiPlan(rootKind = "frame", nodes = listOf(PlanNode("title", Framework.text.id))).validate()
            .resolve(registry, listOf(component)).bind(session, planRoot, "frame").mount()
        mounted.ref(listOf("title"), Framework.text).get().setText(R.string.plan_label)
        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        adapter = object : UiListAdapter<Item, Holder>(executor, { it.copy() }, { it.id },
            object : DiffUtil.ItemCallback<Item>() {
                override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
            }, { throw IllegalStateException(it) }) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context))
            override fun bindFull(holder: Holder, item: Item) { holder.text.text = item.label }
        }
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter.submitList(listOf(Item(1, "Published RecyclerView adapter")))
        UiPrewarmer.attach(session).submit(PrewarmWork({ true }, { true }, {}, {}))
    }

    override fun onDestroy() {
        recycler.adapter = null
        adapter.close()
        mounted.close()
        session.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
