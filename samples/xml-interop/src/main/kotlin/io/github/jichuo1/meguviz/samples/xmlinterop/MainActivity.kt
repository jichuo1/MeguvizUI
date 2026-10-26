package io.github.jichuo1.meguviz.samples.xmlinterop

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.linearParams

class FactoryLabel(context: Context, attrs: AttributeSet) : AppCompatTextView(context, attrs)

class MainActivity : AppCompatActivity() {
    private lateinit var session: UiSession
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        changed = savedInstanceState?.getBoolean("changed") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        val themed = ContextThemeWrapper(this, R.style.PanelTheme)
        // AppCompat has installed its Factory2; cloning keeps it intact for the themed XML.
        val inflater = layoutInflater.cloneInContext(themed)
        val callerFactory = checkNotNull(inflater.factory2)
        session = UiSession(CreationEnvironment(themed))
        val panel = session.inflate(inflater, R.layout.native_panel, root, ViewKey("native-panel"))
        check(inflater.factory2 === callerFactory)
        check((panel.get() as LinearLayout).getChildAt(0) is AppCompatTextView)
        val label = session.borrow(panel.get().findViewById<TextView>(R.id.xml_label), ViewKey("borrowed-label"), ComponentType("native.label", TextView::class.java))
        label.get().setText(if (changed) R.string.changed else R.string.description)
        val binding = session.bind(label, Properties.plainText())
        val button = LinearScope(session, root).button(ViewKey("toggle"), linearParams()) { setText(R.string.toggle) }
        session.click(button) {
            changed = !changed
            binding.submit(getString(if (changed) R.string.changed else R.string.description))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("changed", changed)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { session.close(); super.onDestroy() }
}
