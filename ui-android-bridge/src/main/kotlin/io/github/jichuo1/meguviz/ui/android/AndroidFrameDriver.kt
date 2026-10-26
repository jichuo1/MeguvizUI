package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler
import io.github.jichuo1.meguviz.android.runtime.core.FrameTick
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/** Owns one channel on a caller-owned real MeguvizAndroid scheduler. Never creates another driver. */
class AndroidFrameDriver(
    private val dispatcher: OwnerDispatcher,
    scheduler: FrameScheduler,
    identity: Any,
) : UiFrameDriver, AutoCloseable {
    override val owner: String = "meguviz-android.runtime"
    private class Request(var callback: ((FrameBudget) -> Unit)?)
    private var pending: Request? = null
    private var channel: FrameScheduler.FrameChannel? = null
    private var closeAction: (() -> Unit)? = null
    var isClosed: Boolean = false
        private set
    var lastFrameSequence: Long? = null
        private set

    init {
        dispatcher.checkOwner()
        channel = scheduler.openChannel(identity, { tick, _ -> deliver(tick) }, 1).also { registered ->
            registered.onClose { closedByUpstream() }
        }
    }

    /** A channel close invalidates pending callbacks before notifying its UI owner. */
    fun onClose(action: () -> Unit) {
        dispatcher.checkOwner()
        check(!isClosed && closeAction == null) { "Frame driver closed or close listener already owned" }
        closeAction = action
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        dispatcher.checkOwner()
        check(!isClosed) { "Android frame channel closed" }
        check(pending == null) { "A UI session may have only one pending frame request" }
        val request = Request(callback)
        pending = request
        try { checkNotNull(channel).requestFrame() }
        catch (failure: Throwable) {
            if (pending === request) pending = null
            request.callback = null
            throw failure
        }
        return FrameTicket {
            dispatcher.checkOwner()
            request.callback = null
            if (pending === request) {
                pending = null
                channel?.cancelPending()
            }
        }
    }

    private fun deliver(tick: FrameTick): Int {
        dispatcher.checkOwner()
        val request = pending ?: return 0
        pending = null
        val callback = request.callback
        request.callback = null
        if (isClosed || callback == null) return 0
        lastFrameSequence = tick.sequence()
        // One upstream work unit is one bounded UI drain, not an unbounded second mailbox.
        callback(tick.toUiBudget(owner))
        return 1
    }

    private fun closedByUpstream() {
        isClosed = true
        pending?.callback = null
        pending = null
        channel = null
        val action = closeAction
        closeAction = null
        action?.invoke()
    }

    override fun close() {
        dispatcher.checkOwner()
        if (isClosed) return
        pending?.callback = null
        pending = null
        val registered = channel
        channel = null
        try { registered?.close() }
        finally { if (!isClosed) closedByUpstream() }
    }
}

internal fun FrameTick.toUiBudget(owner: String): FrameBudget = FrameBudget(
    frameTimeNanos = frameTimeNanos(),
    deadlineNanos = deadlineNanos(),
    estimated = true,
    source = "$owner:${budgetSource()}",
)
