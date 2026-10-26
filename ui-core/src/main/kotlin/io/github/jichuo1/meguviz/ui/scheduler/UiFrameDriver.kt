package io.github.jichuo1.meguviz.ui.scheduler

/** Copied monotonic timing values. No platform callback object escapes its callback. */
data class FrameBudget(
    val frameTimeNanos: Long,
    val deadlineNanos: Long? = null,
    val estimated: Boolean = true,
    val source: String = "unknown",
)

fun interface FrameTicket {
    /** Releases the driver's callback reference. A racing callback must still be invalidated. */
    fun cancel()
}

/** One immutable driver is selected when a session is assembled. Methods run on its owner. */
interface UiFrameDriver {
    val owner: String

    /** Must enqueue one opportunity, never invoke [callback] synchronously. */
    fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket
}
