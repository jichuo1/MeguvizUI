package io.github.jichuo1.meguviz.ui.scheduler

import android.os.Looper
import android.view.Choreographer

/** Default platform driver. Android bridge sessions supply their own driver instead. */
class ChoreographerFrameDriver : UiFrameDriver {
    override val owner: String = "android.choreographer"
    private val choreographer: Choreographer

    init {
        checkMainThread()
        choreographer = Choreographer.getInstance()
    }

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        checkMainThread()
        var pending: ((FrameBudget) -> Unit)? = callback
        val platformCallback = Choreographer.FrameCallback { time ->
            val consumer = pending
            pending = null
            consumer?.invoke(FrameBudget(time, source = owner))
        }
        choreographer.postFrameCallback(platformCallback)
        return FrameTicket {
            checkMainThread()
            pending = null
            choreographer.removeFrameCallback(platformCallback)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Frame driver requires the main thread" }
    }
}
