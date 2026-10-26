package fixtures

import android.view.View
import io.github.jichuo1.meguviz.android.window.WindowSession
import io.github.jichuo1.meguviz.ui.android.AndroidUiBinding

fun openOwnedUi(window: WindowSession, root: View): AndroidUiBinding = AndroidUiBinding.open(window, root)
