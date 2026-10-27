package io.github.jichuo1.meguviz.samples.generated

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams

class GeneratedInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val name = "generated_factory_matches_direct_factory"
        sendStatus(1, Bundle().apply { putString("class", GeneratedInstrumentation::class.java.name); putString("test", name); putInt("numtests", 1); putInt("current", 1) })
        var error: Throwable? = null
        try { runOnMainSync {
            try {
                val session = UiSession(CreationEnvironment(targetContext))
                try {
                    val root = FrameLayout(targetContext)
                    val scope = FrameScope(session, root)
                    val registry = RegistrySnapshot.install(listOf(GeneratedLabelUiGenerated.registration()))
                    val factory = registry.resolve(GeneratedLabelUiGenerated.type)
                    val generated: GeneratedLabelViewRef = scope.view(ViewKey("generated"), GeneratedLabelUiGenerated.type, frameParams(100, 40), factory)
                    val direct = scope.view(ViewKey("direct"), GeneratedLabelUiGenerated.type, frameParams(100, 40), ViewFactory { GeneratedLabel(it.context) })
                    check(generated.get().javaClass == direct.get().javaClass)
                    check(generated.get().context === direct.get().context)
                    check(generated.get().layoutParams.width == direct.get().layoutParams.width && generated.get().layoutParams.height == direct.get().layoutParams.height)
                    check(registry.candidateChecks == 1L)
                } finally { session.close() }
            } catch (failure: Throwable) { error = failure }
        } } catch (failure: Throwable) { error = failure }
        val status = Bundle().apply { putString("class", GeneratedInstrumentation::class.java.name); putString("test", name); putInt("numtests", 1); putInt("current", 1) }
        error?.let { status.putString("stack", it.stackTraceToString()) }
        sendStatus(if (error == null) 0 else -2, status)
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "Generated factory: 1 test, ${if (error == null) 0 else 1} failures\n") })
    }
}
