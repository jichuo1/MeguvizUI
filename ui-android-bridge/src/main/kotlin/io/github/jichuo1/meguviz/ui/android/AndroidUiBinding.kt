package io.github.jichuo1.meguviz.ui.android

import android.view.View
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.ViewScope
import io.github.jichuo1.meguviz.android.window.WindowSession
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.SessionState
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.scheduler.SchedulerState

/** Owns a new upstream ViewScope and a UI session; the caller retains its WindowSession and root. */
class AndroidUiBinding private constructor(
    val ui: UiSession,
    root: View,
    scope: ViewScope,
    driver: AndroidWindowFrameDriver,
) : AutoCloseable {
    private val dispatcher = scope.dispatcher()
    private var root: View? = root
    private var scope: ViewScope? = scope
    private var driver: AndroidWindowFrameDriver? = driver
    private var registration: Registration? = null
    private var closed = false
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewDetachedFromWindow(view: View) {
            if (!closed) {
                this@AndroidUiBinding.driver?.setAttached(false)
                if (ui.state == SessionState.ACTIVE) ui.detach()
            }
        }
        override fun onViewAttachedToWindow(view: View) {
            if (!closed && ui.state == SessionState.DETACHED && ui.scheduler.state == SchedulerState.PAUSED) ui.reattach()
            if (!closed) this@AndroidUiBinding.driver?.setAttached(true)
        }
    }

    private fun install() {
        dispatcher.checkOwner()
        val ownedScope = checkNotNull(scope)
        val target = checkNotNull(root)
        registration = ownedScope.own(Registration(dispatcher) { close() })
        target.addOnAttachStateChangeListener(attachment)
        checkNotNull(driver).onClose { close() }
        ui.onClose { close() }
    }

    override fun close() {
        dispatcher.checkOwner()
        if (closed) return
        closed = true
        val target = root; root = null
        val ownedScope = scope; scope = null
        val ownedDriver = driver; driver = null
        val ownedRegistration = registration; registration = null
        closeAll(listOf(
            { target?.removeOnAttachStateChangeListener(attachment) },
            { ui.close() },
            { ownedDriver?.close() },
            { ownedRegistration?.close() },
            { ownedScope?.close() },
        ))
    }

    companion object {
        /** The supplied root must not already be owned by another ViewScope in this window. */
        fun open(window: WindowSession, root: View): AndroidUiBinding {
            window.requirePermission(WindowSession.Permission.APPLY_LOCAL)
            val scope = window.scope(root)
            var driver: AndroidWindowFrameDriver? = null
            var ui: UiSession? = null
            var binding: AndroidUiBinding? = null
            try {
                driver = AndroidWindowFrameDriver(window, scope, root.isAttachedToWindow)
                val resources = scope.resources()
                ui = UiSession(CreationEnvironment(root.context, resources.state().namespace(), androidResourceAuthority(resources)), driver)
                binding = AndroidUiBinding(ui, root, scope, driver)
                binding.install()
                return binding
            } catch (failure: Throwable) {
                try {
                    if (binding != null) binding.close()
                    else closeAll(listOf({ ui?.close() }, { driver?.close() }, { scope.close() }))
                } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
