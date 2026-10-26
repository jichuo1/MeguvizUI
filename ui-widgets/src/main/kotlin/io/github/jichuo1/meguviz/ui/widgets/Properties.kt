package io.github.jichuo1.meguviz.ui.widgets

import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.EffectMask
import io.github.jichuo1.meguviz.ui.PropertyOwnership
import io.github.jichuo1.meguviz.ui.PropertySpec

object Properties {
    /** Explicitly for immutable plain labels without editing/filter side effects. */
    fun plainText() = PropertySpec<TextView, String>(
        id = "text", effect = EffectMask.LAYOUT or EffectMask.SEMANTICS,
        ownership = PropertyOwnership.SHARED_READ, coalescible = true,
        equivalent = { a, b -> a == b }, read = { it.text.toString() },
        comparisonAllowed = { it.text is String && it !is android.widget.EditText && it.filters.isEmpty() },
        write = { view, text -> view.text = text },
    )

    /** Each call reaches TextView; callers must not mutate queued input. */
    fun richText() = PropertySpec<TextView, CharSequence>(
        id = "text", effect = EffectMask.LAYOUT or EffectMask.SEMANTICS,
        snapshot = { android.text.SpannedString(it) }, write = { view, text -> view.text = text },
    )

    fun textColor() = PropertySpec<TextView, Int>(
        id = "textColor", effect = EffectMask.PAINT, ownership = PropertyOwnership.SHARED_READ,
        resourceSensitive = true,
        coalescible = true, equivalent = { a, b -> a == b }, read = { it.currentTextColor }, write = { view, color -> view.setTextColor(color) },
    )

    fun visibility() = PropertySpec<View, Int>(
        id = "visibility", effect = EffectMask.LAYOUT or EffectMask.SEMANTICS,
        ownership = PropertyOwnership.SHARED_READ, coalescible = true,
        equivalent = { a, b -> a == b }, read = { it.visibility }, write = { view, value -> view.visibility = value },
    )

    /** Commands preserve repeated attempts. Readback observes listener rejection/rollback. */
    fun checked() = PropertySpec<CompoundButton, Boolean>(
        id = "checked", effect = EffectMask.PAINT or EffectMask.SEMANTICS,
        ownership = PropertyOwnership.ALWAYS_WRITE, read = { it.isChecked }, write = { view, checked -> view.isChecked = checked },
    )
}
