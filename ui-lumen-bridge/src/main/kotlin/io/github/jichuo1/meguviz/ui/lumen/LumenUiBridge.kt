package io.github.jichuo1.meguviz.ui.lumen

import android.view.View
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef

/** Lumen retains all material/sampling/animation decisions. This bridge owns only registrations. */
class LumenUiBridge(private val ui: UiSession, lumen: LumenSurfaceSession, private val ownsSession: Boolean = false) : AutoCloseable {
    private var lumen: LumenSurfaceSession? = lumen
    private val registrations = mutableSetOf<SurfaceRegistration>()
    init { ui.onClose { close() } }

    fun <V : View> bind(ref: ViewRef<V>, options: LumenSurfaceOptions, source: View? = null): SurfaceRegistration {
        ui.assertOwner()
        val engine = checkNotNull(lumen) { "Lumen bridge closed" }
        val ownership = ui.reserveProperties(ref, setOf("background"))
        val binding = try { engine.bind(ref.get(), options, source) } catch (failure: Throwable) { ownership.close(); throw failure }
        val registration = SurfaceRegistration(ui, binding, ownership) { registrations.remove(it) }
        registrations += registration
        ui.onRelease(ref) { registration.close() }
        return registration
    }

    /** Shared Lumen sessions must be controlled by their actual owner. */
    fun pause() { ui.assertOwner(); check(ownsSession); checkNotNull(lumen).pause() }
    fun resume() { ui.assertOwner(); check(ownsSession); checkNotNull(lumen).resume() }

    override fun close() {
        ui.assertOwner()
        val engine = lumen ?: return
        lumen = null
        val failures = mutableListOf<Throwable>()
        registrations.toList().forEach { try { it.close() } catch (failure: Throwable) { failures += failure } }
        registrations.clear()
        if (ownsSession) try { engine.close() } catch (failure: Throwable) { failures += failure }
        if (failures.isNotEmpty()) { val first = failures.first(); failures.drop(1).forEach(first::addSuppressed); throw first }
    }
}

class SurfaceRegistration internal constructor(
    private val ui: UiSession,
    binding: LumenSurfaceBinding,
    ownership: AutoCloseable,
    onClosed: (SurfaceRegistration) -> Unit,
) : AutoCloseable {
    private var binding: LumenSurfaceBinding? = binding
    private var ownership: AutoCloseable? = ownership
    private var onClosed: ((SurfaceRegistration) -> Unit)? = onClosed
    fun update(options: LumenSurfaceOptions) { ui.assertOwner(); checkNotNull(binding) { "Surface registration closed" }.update(options) }
    override fun close() {
        ui.assertOwner()
        val current = binding ?: return
        binding = null
        val lease = ownership; ownership = null
        val callback = onClosed; onClosed = null
        try { current.close() } finally { try { lease?.close() } finally { callback?.invoke(this) } }
    }
}
