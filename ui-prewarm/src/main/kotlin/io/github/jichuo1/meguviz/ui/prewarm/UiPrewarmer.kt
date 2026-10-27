package io.github.jichuo1.meguviz.ui.prewarm

import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.scheduler.SchedulerState
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import java.util.ArrayDeque

enum class PrewarmStop { CANCELLED, DETACHED, LOW_MEMORY, RESOURCE_CHANGED, OVER_BUDGET, UNIT_LIMIT, FAILED, SCHEDULER_UNAVAILABLE, CLOSED }

sealed class PrewarmSubmission {
    data object Accepted : PrewarmSubmission()
    data object Closed : PrewarmSubmission()
    data object AtCapacity : PrewarmSubmission()
    data object SchedulerUnavailable : PrewarmSubmission()
}

/** The caller owns staged objects and must release them in [discard] unless [ready] accepts them. */
class PrewarmWork(
    val isCurrent: () -> Boolean,
    val step: () -> Boolean,
    val ready: () -> Unit,
    val discard: (PrewarmStop) -> Unit,
)

/**
 * Explicitly opted-in work that runs on the scheduler owner, at most one unit per frame.
 * A false [PrewarmWork.step] means more work is needed; true transfers ownership via ready.
 * The time limit detects a slow unit after it returns: it cannot preempt a View constructor.
 */
class UiPrewarmer(
    private val scheduler: UiScheduler,
    private val assertOwner: () -> Unit,
    val capacity: Int = 4,
    val maxUnitsPerWork: Int = 32,
    val maxUnitNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Entry(val work: PrewarmWork, var units: Int = 0)
    private val queue = ArrayDeque<Entry>()
    private var scheduled = false
    private var closed = false
    private var discarding = false
    private var running: Entry? = null
    private var deferredStop: PrewarmStop? = null
    val pendingCount: Int get() { assertOwner(); return queue.size }

    init {
        assertOwner()
        require(capacity > 0 && maxUnitsPerWork > 0 && maxUnitNanos > 0)
    }

    companion object {
        /** Call [onDetached] before the session scheduler is paused on detach. */
        fun attach(session: UiSession, capacity: Int = 4, maxUnitsPerWork: Int = 32, maxUnitNanos: Long = 2_000_000): UiPrewarmer {
            session.assertOwner()
            val prewarmer = UiPrewarmer(session.scheduler, session::assertOwner, capacity, maxUnitsPerWork, maxUnitNanos)
            session.onClose(prewarmer::close)
            return prewarmer
        }
    }

    /** On rejection ownership stays with the caller; discard is not invoked. */
    fun submit(work: PrewarmWork): PrewarmSubmission {
        assertOwner()
        if (closed) return PrewarmSubmission.Closed
        if (discarding) return PrewarmSubmission.SchedulerUnavailable
        if (!checkScheduler()) return PrewarmSubmission.SchedulerUnavailable
        if (queue.size >= capacity) return PrewarmSubmission.AtCapacity
        val entry = Entry(work)
        queue.addLast(entry)
        if (!scheduled && !schedule()) {
            queue.removeLastOccurrence(entry)
            return PrewarmSubmission.SchedulerUnavailable
        }
        return PrewarmSubmission.Accepted
    }

    fun cancel() = discardAll(PrewarmStop.CANCELLED)
    fun onDetached() = discardAll(PrewarmStop.DETACHED)
    fun onLowMemory() = discardAll(PrewarmStop.LOW_MEMORY)
    fun onResourcesChanged() = discardAll(PrewarmStop.RESOURCE_CHANGED)

    /** Notify the queue after another operation faults or pauses its shared scheduler. */
    fun checkScheduler(): Boolean {
        assertOwner()
        if (scheduler.state == SchedulerState.ACTIVE) return true
        discardAll(PrewarmStop.SCHEDULER_UNAVAILABLE)
        return false
    }

    override fun close() {
        assertOwner()
        if (closed) return
        closed = true
        discardAll(PrewarmStop.CLOSED)
    }

    private fun schedule(): Boolean {
        if (queue.isEmpty() || closed || scheduled) return true
        scheduled = true
        val submission = scheduler.submitCommand(isValid = { scheduled && !closed && queue.isNotEmpty() }) {
            scheduled = false
            runUnit()
        }
        if (submission is SubmitResult.Rejected || scheduler.state != SchedulerState.ACTIVE) {
            scheduled = false
            return false
        }
        return true
    }

    private fun runUnit() {
        assertOwner()
        val entry = queue.peekFirst() ?: return
        val work = entry.work
        running = entry
        var primaryFailure: Throwable? = null
        try {
            if (!work.isCurrent()) {
                if (queue.peekFirst() === entry) finishDiscard(entry, PrewarmStop.RESOURCE_CHANGED)
            } else if (queue.peekFirst() === entry) {
                val started = nanoTime()
                val complete = work.step()
                val elapsed = nanoTime() - started
                entry.units++
                val currentAfter = queue.peekFirst() === entry && work.isCurrent()
                when {
                    queue.peekFirst() !== entry -> Unit // A callback explicitly cancelled or closed the queue.
                    !currentAfter -> finishDiscard(entry, PrewarmStop.RESOURCE_CHANGED)
                    elapsed > maxUnitNanos -> finishDiscard(entry, PrewarmStop.OVER_BUDGET)
                    complete -> {
                        queue.removeFirst()
                        try { work.ready() } catch (error: Throwable) {
                            try { work.discard(PrewarmStop.FAILED) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                            throw error
                        }
                    }
                    entry.units >= maxUnitsPerWork -> finishDiscard(entry, PrewarmStop.UNIT_LIMIT)
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            if (queue.peekFirst() === entry) {
                try { finishDiscard(entry, PrewarmStop.FAILED) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            try { discardAll(PrewarmStop.FAILED) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        } finally {
            running = null
            val reason = deferredStop
            deferredStop = null
            var cleanupFailure: Throwable? = null
            try {
                if (reason != null) work.discard(reason)
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                if (!closed && queue.isNotEmpty() && !schedule()) discardAll(PrewarmStop.SCHEDULER_UNAVAILABLE)
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (cleanupFailure != null) {
                if (primaryFailure == null) throw cleanupFailure else primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun finishDiscard(entry: Entry, reason: PrewarmStop) {
        check(queue.removeFirst() === entry)
        entry.work.discard(reason)
    }

    private fun discardAll(reason: PrewarmStop) {
        assertOwner()
        if (discarding) return
        discarding = true
        scheduled = false
        var failure: Throwable? = null
        try {
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                if (entry === running) {
                    deferredStop = deferredStop ?: reason
                    continue
                }
                try { entry.work.discard(reason) } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            scheduler.discardInvalid()
        } finally {
            discarding = false
        }
        if (failure != null) throw failure
    }
}
