package io.github.jichuo1.meguviz.ui.widgets

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.ParentPolicy
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.UiDsl
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import kotlin.math.roundToInt

val MatchParent: Int = ViewGroup.LayoutParams.MATCH_PARENT
val WrapContent: Int = ViewGroup.LayoutParams.WRAP_CONTENT

@JvmInline value class Dp(val value: Float) {
    fun pixels(context: Context): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).roundToInt()
}
@JvmInline value class Sp(val value: Float) {
    fun pixels(context: Context): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)
}

object LinearPolicy : ParentPolicy<LinearLayout, LinearLayout.LayoutParams>
object FramePolicy : ParentPolicy<FrameLayout, FrameLayout.LayoutParams>
object ScrollPolicy : ParentPolicy<ScrollView, FrameLayout.LayoutParams>

fun linearParams(width: Int = MatchParent, height: Int = WrapContent, configure: LinearLayout.LayoutParams.() -> Unit = {}) = LinearLayout.LayoutParams(width, height).apply(configure)
fun frameParams(width: Int = MatchParent, height: Int = WrapContent, configure: FrameLayout.LayoutParams.() -> Unit = {}) = FrameLayout.LayoutParams(width, height).apply(configure)

object Framework {
    val text = ComponentType("framework.text", TextView::class.java)
    val edit = ComponentType("framework.edit", EditText::class.java)
    val button = ComponentType("framework.button", Button::class.java)
    val image = ComponentType("framework.image", ImageView::class.java)
    val linear = ComponentType("framework.linear", LinearLayout::class.java)
    val frame = ComponentType("framework.frame", FrameLayout::class.java)
    val scroll = ComponentType("framework.scroll", ScrollView::class.java)
    val textFactory = ViewFactory { env: CreationEnvironment -> TextView(env.context) }
    val editFactory = ViewFactory { env: CreationEnvironment -> EditText(env.context) }
    val buttonFactory = ViewFactory { env: CreationEnvironment -> Button(env.context) }
    val imageFactory = ViewFactory { env: CreationEnvironment -> ImageView(env.context) }
    val linearFactory = ViewFactory { env: CreationEnvironment -> LinearLayout(env.context) }
    val frameFactory = ViewFactory { env: CreationEnvironment -> FrameLayout(env.context) }
    val scrollFactory = ViewFactory { env: CreationEnvironment -> ScrollView(env.context) }
}

/** Direct scope calls; no node plan, provider lookup, synthetic XML, or reflective LP. */
@UiDsl
open class ParentScope<P : ViewGroup, LP : ViewGroup.LayoutParams>(
    val session: UiSession,
    val parent: P,
    val policy: ParentPolicy<P, LP>,
) {
    fun <V : View> view(key: ViewKey, type: ComponentType<V>, params: LP, factory: ViewFactory<V>, configure: V.() -> Unit = {}): ViewRef<V> =
        session.mount(parent, policy, key, type, params, factory, configure)

    fun text(key: ViewKey, params: LP, configure: TextView.() -> Unit = {}) = view(key, Framework.text, params, Framework.textFactory, configure)
    fun edit(key: ViewKey, params: LP, configure: EditText.() -> Unit = {}) = view(key, Framework.edit, params, Framework.editFactory, configure)
    fun button(key: ViewKey, params: LP, configure: Button.() -> Unit = {}) = view(key, Framework.button, params, Framework.buttonFactory, configure)
    fun image(key: ViewKey, params: LP, configure: ImageView.() -> Unit = {}) = view(key, Framework.image, params, Framework.imageFactory, configure)

    fun linear(key: ViewKey, params: LP, configure: LinearLayout.() -> Unit = {}, children: LinearScope.() -> Unit): ViewRef<LinearLayout> {
        val ref = view(key, Framework.linear, params, Framework.linearFactory, configure)
        try { LinearScope(session, ref.get()).children() } catch (failure: Throwable) { session.release(ref); throw failure }
        return ref
    }
    fun frame(key: ViewKey, params: LP, configure: FrameLayout.() -> Unit = {}, children: FrameScope.() -> Unit): ViewRef<FrameLayout> {
        val ref = view(key, Framework.frame, params, Framework.frameFactory, configure)
        try { FrameScope(session, ref.get()).children() } catch (failure: Throwable) { session.release(ref); throw failure }
        return ref
    }
}

class LinearScope(session: UiSession, parent: LinearLayout) : ParentScope<LinearLayout, LinearLayout.LayoutParams>(session, parent, LinearPolicy)
class FrameScope(session: UiSession, parent: FrameLayout) : ParentScope<FrameLayout, FrameLayout.LayoutParams>(session, parent, FramePolicy)
