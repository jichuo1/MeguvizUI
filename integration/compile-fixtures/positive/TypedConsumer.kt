package fixtures

import android.widget.LinearLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.state.StateValue
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.linearParams

fun title(session: UiSession, parent: LinearLayout): ViewRef<TextView> =
    LinearScope(session, parent).text(ViewKey("title"), linearParams()) { text = "Typed consumer" }

fun pureSnapshot(): StateValue.Record = StateValue.Record(mapOf("title" to StateValue.Text("saved")))
