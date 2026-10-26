package fixtures

import android.view.View
import io.github.jichuo1.meguviz.ui.state.StateValue

fun impureState(view: View) = StateValue.Record(mapOf("view" to view))
