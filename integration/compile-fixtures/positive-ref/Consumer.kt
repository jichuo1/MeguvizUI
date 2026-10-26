package fixtures

import android.view.View
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.ref.RefBindingSession

fun <V : View> bindCodeAndView(ui: UiSession, code: CodeScope, ref: ViewRef<V>) {
    val codeBinding = RefBindingSession.attach(ui, code)
    val lease = codeBinding.bindView(ref, resources = true)
    ui.scheduler.submitBatch(listOf(lease.operation("focus") { it.requestFocus() }))
}

fun <V : View> resolveView(binding: RefBindingSession, type: Class<V>) = binding.viewFactory(type)
