package fixtures

import android.widget.Button
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.ViewRef

fun wrongRef(text: ViewRef<TextView>): ViewRef<Button> = text
