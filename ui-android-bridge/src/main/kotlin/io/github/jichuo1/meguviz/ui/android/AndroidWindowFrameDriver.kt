package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.core.FrameTick
import io.github.jichuo1.meguviz.android.runtime.core.Registration
import io.github.jichuo1.meguviz.android.window.WindowSession
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/** Window lifecycle gating is performed by the real upstream WindowFrameChannel. */
internal class AndroidWindowFrameDriver(window: WindowSession, identity: Any, initiallyAttached: Boolean) : UiFrameDriver, AutoCloseable {
    override val owner = "meguviz-android.window"
    private val dispatcher = window.dispatcher()
    private class Request(var callback: ((FrameBudget) -> Unit)?)
    private var pending: Request? = null
    private var channel: WindowSession.WindowFrameChannel? = null
    private var registration: Registration? = null
    private var closed = false
    private var attached = initiallyAttached
    private var closeAction: (() -> Unit)? = null

    init {
        dispatcher.checkOwner()
        channel = window.openFrameChannel(identity, { tick, _ -> deliver(tick) }, 1)
        try { registration = window.own(Registration(dispatcher) { close() }) }
        catch (failure: Throwable) {
            try { channel?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            channel = null
            throw failure
        }
    }

    fun onClose(action: () -> Unit) {
        dispatcher.checkOwner()
        check(!closed && closeAction == null)
        closeAction = action
    }

    fun setAttached(value: Boolean) {
        dispatcher.checkOwner()
        if (closed || attached == value) return
        attached = value
        if (!value) channel?.cancelPending()
        else if (pending != null) checkNotNull(channel).requestFrame()
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        dispatcher.checkOwner()
        check(!closed && pending == null) { "Window driver closed or frame already pending" }
        val request = Request(callback)
        pending = request
        try { if (attached) checkNotNull(channel).requestFrame() }
        catch (failure: Throwable) {
            if (pending === request) pending = null
            request.callback = null
            throw failure
        }
        return FrameTicket {
            dispatcher.checkOwner()
            request.callback = null
            if (pending === request) { pending = null; channel?.cancelPending() }
        }
    }

    private fun deliver(tick: FrameTick): Int {
        val request = pending ?: return 0
        pending = null
        val callback = request.callback
        request.callback = null
        if (closed || !attached || callback == null) return 0
        callback(tick.toUiBudget(owner))
        return 1
    }

    override fun close() {
        dispatcher.checkOwner()
        if (closed) return
        closed = true
        pending?.callback = null
        pending = null
        val ownedChannel = channel; channel = null
        val ownedRegistration = registration; registration = null
        val action = closeAction; closeAction = null
        closeAll(listOf({ ownedChannel?.close() }, { ownedRegistration?.close() }, { action?.invoke() }))
    }
}

internal fun closeAll(actions: List<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
    }
    failure?.let { throw it }
}
