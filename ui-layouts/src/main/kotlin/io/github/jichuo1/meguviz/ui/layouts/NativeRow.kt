package io.github.jichuo1.meguviz.ui.layouts

import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.ParentScope

/** Limits the initial native child set; later raw View mutations remain caller-owned. */
data class NativeRowConstraints(val maxChildren: Int = 8) {
    init { require(maxChildren in 1..32) { "A row must allow between 1 and 32 children" } }
    fun checkChildCount(count: Int) {
        require(count in 0..maxChildren) { "Native row child count exceeds its declared bound" }
    }
}

/**
 * An opt-in horizontal native row. LinearLayout remains responsible for measurement,
 * margins, baseline, RTL, focus and accessibility. No measurement speedup is assumed.
 */
fun <P : ViewGroup, LP : ViewGroup.LayoutParams> ParentScope<P, LP>.nativeRow(
    key: ViewKey,
    params: LP,
    constraints: NativeRowConstraints = NativeRowConstraints(),
    configure: LinearLayout.() -> Unit = {},
    children: LinearScope.() -> Unit,
): ViewRef<LinearLayout> = linear(key, params, configure = {
    orientation = LinearLayout.HORIZONTAL
    configure()
    require(orientation == LinearLayout.HORIZONTAL) { "A native row must remain horizontal" }
}, children = {
    children()
    constraints.checkChildCount(parent.childCount)
})
