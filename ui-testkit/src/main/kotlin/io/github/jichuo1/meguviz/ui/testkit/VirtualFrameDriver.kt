package io.github.jichuo1.meguviz.ui.testkit

import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver

/** Deterministic test-only frame source; callbacks requested during tick wait for another tick. */
class VirtualFrameDriver(override val owner: String = "test.virtual") : UiFrameDriver {
    private val callbacks = linkedMapOf<Long, (FrameBudget) -> Unit>()
    private var nextId = 1L
    val events: MutableList<String> = mutableListOf()
    val pendingCount: Int get() = callbacks.size

    override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
        val id = nextId++
        callbacks[id] = callback
        events += "request:$id"
        return FrameTicket {
            if (callbacks.remove(id) != null) events += "cancel:$id"
        }
    }

    fun tick(budget: FrameBudget = FrameBudget(0, source = owner)) {
        val frame = callbacks.keys.toList()
        frame.forEach { id ->
            val callback = callbacks.remove(id) ?: return@forEach
            events += "tick:$id"
            callback(budget)
        }
    }
}
