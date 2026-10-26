package fixtures

import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams

fun wrongNestedReceiver(scope: FrameScope) = with(scope) {
    linear(ViewKey("row"), frameParams()) {
        // Without UiDsl this could implicitly select the outer FrameScope.
        text(ViewKey("must-not-mount-in-outer-frame"), frameParams())
    }
}
