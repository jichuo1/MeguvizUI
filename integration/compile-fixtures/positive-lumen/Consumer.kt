package fixtures

import android.view.View
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.lumen.LumenUiBridge

fun <V : View> bindVisual(ui: UiSession, engine: LumenSurfaceSession, ref: ViewRef<V>, options: LumenSurfaceOptions) =
    LumenUiBridge(ui, engine).bind(ref, options)
