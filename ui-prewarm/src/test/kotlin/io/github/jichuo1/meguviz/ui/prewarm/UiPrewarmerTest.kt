package io.github.jichuo1.meguviz.ui.prewarm

import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.*
import org.junit.Test

class UiPrewarmerTest {
    private val driver = VirtualFrameDriver()
    private val scheduler = UiScheduler(driver, {}, nanoTime = { 0L })
    private val events = mutableListOf<String>()

    private fun work(
        current: () -> Boolean = { true },
        step: () -> Boolean = { true },
    ) = PrewarmWork(current, step, { events += "ready" }, { events += "discard:$it" })

    @Test fun oneUnitPerFrameUsesOnlyTheExistingDriver() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        var units = 0
        assertEquals(PrewarmSubmission.Accepted, queue.submit(work(step = { ++units == 3 })))
        assertEquals(1, driver.pendingCount)
        repeat(2) { driver.tick(); assertTrue(events.isEmpty()); assertEquals(it + 1, units) }
        driver.tick()
        assertEquals(listOf("ready"), events)
        assertEquals(0, queue.pendingCount)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun capacityRejectionLeavesOwnershipWithCallerAndCancelClearsPending() {
        val queue = UiPrewarmer(scheduler, {}, capacity = 1, maxUnitNanos = Long.MAX_VALUE)
        assertEquals(PrewarmSubmission.Accepted, queue.submit(work()))
        assertEquals(PrewarmSubmission.AtCapacity, queue.submit(work()))
        queue.cancel()
        assertEquals(listOf("discard:CANCELLED"), events)
        assertEquals(0, driver.pendingCount)
        driver.tick()
        assertEquals(1, events.size)
    }

    @Test fun changedGenerationIsDiscardedBeforeAndAfterAUnit() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        var current = true
        queue.submit(work(current = { current }, step = { current = false; true }))
        driver.tick()
        assertEquals(listOf("discard:RESOURCE_CHANGED"), events)
        queue.submit(work(current = { false }))
        driver.tick()
        assertEquals(listOf("discard:RESOURCE_CHANGED", "discard:RESOURCE_CHANGED"), events)
    }

    @Test fun lowMemoryDetachAndCloseReleaseStagedReferences() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work()); queue.onLowMemory()
        queue.submit(work()); queue.onDetached()
        queue.submit(work()); queue.close()
        assertEquals(listOf("discard:LOW_MEMORY", "discard:DETACHED", "discard:CLOSED"), events)
        assertEquals(PrewarmSubmission.Closed, queue.submit(work()))
        assertEquals(0, driver.pendingCount)
    }

    @Test fun longUnitIsDetectedAfterItReturnsAndDoesNotPublishResult() {
        var time = 0L
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = 5, nanoTime = { time })
        queue.submit(work(step = { time += 6; true }))
        driver.tick()
        assertEquals(listOf("discard:OVER_BUDGET"), events)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun unitLimitDoesNotAllowUnboundedRescheduling() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitsPerWork = 2, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work(step = { false }))
        driver.tick(); driver.tick()
        assertEquals(listOf("discard:UNIT_LIMIT"), events)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun reentrantCancelReleasesInFlightOnlyAfterUnitReturns() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work(step = {
            events += "before"
            queue.cancel()
            events += "after"
            true
        }))
        driver.tick()
        assertEquals(listOf("before", "after", "discard:CANCELLED"), events)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun fullSchedulerRejectsWithoutTakingWorkOwnership() {
        val driver = VirtualFrameDriver()
        val full = UiScheduler(driver, {}, commandCapacity = 1, nanoTime = { 0L })
        full.submitCommand {}
        val queue = UiPrewarmer(full, {})
        assertEquals(PrewarmSubmission.SchedulerUnavailable, queue.submit(work()))
        assertEquals(0, queue.pendingCount)
        assertTrue(events.isEmpty())
    }

    @Test fun failureDiscardsCurrentAndRemainingTasksWithoutPublishing() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work(step = { error("creation failed") }))
        queue.submit(work())
        driver.tick()
        assertEquals(listOf("discard:FAILED", "discard:FAILED"), events)
        assertEquals(0, queue.pendingCount)
        assertEquals(io.github.jichuo1.meguviz.ui.scheduler.SchedulerState.FAULTED, scheduler.state)
    }

    @Test fun ownerCheckRejectsBackgroundSubmission() {
        val owner = Thread.currentThread()
        val queue = UiPrewarmer(scheduler, { check(Thread.currentThread() === owner) }, maxUnitNanos = Long.MAX_VALUE)
        var failure: Throwable? = null
        val thread = Thread {
            try { queue.submit(work()) } catch (error: Throwable) { failure = error }
        }
        thread.start()
        thread.join()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, queue.pendingCount)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun closeInWorkDefersReleaseUntilUnitReturns() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work(step = { events += "before"; queue.close(); events += "after"; true }))
        driver.tick()
        assertEquals(listOf("before", "after", "discard:CLOSED"), events)
        assertEquals(PrewarmSubmission.Closed, queue.submit(work()))
    }

    @Test fun reentrantCancelCleanupFailureDoesNotReplaceWorkFailure() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(PrewarmWork({ true }, { queue.cancel(); error("work failure") }, {}, { error("cleanup failure") }))
        driver.tick()
        assertEquals("work failure", scheduler.failure?.message)
        assertEquals(0, queue.pendingCount)
    }

    @Test fun resourceValidationMayCancelWithoutFaultingScheduler() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        var checks = 0
        queue.submit(work(current = { checks++; if (checks == 2) { queue.cancel(); false } else true }))
        driver.tick()
        assertEquals(listOf("discard:CANCELLED"), events)
        assertEquals(io.github.jichuo1.meguviz.ui.scheduler.SchedulerState.ACTIVE, scheduler.state)
    }

    @Test fun externalSchedulerFaultRejectsAndReleasesPreviouslyAdmittedWork() {
        val queue = UiPrewarmer(scheduler, {}, maxUnitNanos = Long.MAX_VALUE)
        queue.submit(work())
        scheduler.submitState("unrelated") { error("external failure") }
        driver.tick()
        assertEquals(io.github.jichuo1.meguviz.ui.scheduler.SchedulerState.FAULTED, scheduler.state)
        assertEquals(PrewarmSubmission.SchedulerUnavailable, queue.submit(work()))
        assertEquals(listOf("discard:SCHEDULER_UNAVAILABLE"), events)
        assertEquals(0, queue.pendingCount)
    }
}
