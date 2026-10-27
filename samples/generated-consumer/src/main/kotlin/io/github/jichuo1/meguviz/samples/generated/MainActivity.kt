package io.github.jichuo1.meguviz.samples.generated

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams

@GenerateUiFactory(id = "generated.label")
class GeneratedLabel(context: Context) : TextView(context)

class MainActivity : Activity() {
    private lateinit var session: UiSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        setContentView(root)
        session = UiSession(CreationEnvironment(this))
        val registry = RegistrySnapshot.install(listOf(GeneratedLabelUiGenerated.registration()))
        val factory = registry.resolve(GeneratedLabelUiGenerated.type)
        val label: GeneratedLabelViewRef = FrameScope(session, root).view(
            ViewKey("generated"), GeneratedLabelUiGenerated.type, frameParams(), factory
        ) { setText(R.string.generated_factory_ready) }
        check(label.get().text.toString() == getString(R.string.generated_factory_ready))
    }
    override fun onDestroy() { session.close(); super.onDestroy() }
}
