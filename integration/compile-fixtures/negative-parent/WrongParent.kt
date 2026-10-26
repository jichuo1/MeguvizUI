package fixtures

import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.frameParams

fun wrongParent(scope: LinearScope) = scope.text(ViewKey("wrong"), frameParams())
