package io.github.jichuo1.meguviz.ui.scheduler

import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.*
import org.junit.Test

class SchedulerContractTest {
    private fun scheduler(
        driver: VirtualFrameDriver,
        stateCapacity: Int = 256,
        commandCapacity: Int = 256,
        maxOperations: Int = 64,
        clock: () -> Long = { 0L },
    ) = UiScheduler(driver, {}, stateCapacity, commandCapacity, maxOperations, nanoTime = clock)

    @Test fun mvxF01OnlyOneOutstandingFrameAndNoExtraQueueFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<String>()
        scheduler.submitState("title") { log += "title" }
        scheduler.submitCommand { log += "click" }
        assertEquals(1, driver.pendingCount)
        assertTrue(log.isEmpty())
        driver.tick()
        assertEquals(listOf("title", "click"), log)
        assertEquals(listOf("request:1", "tick:1"), driver.events)
        assertEquals(0, driver.pendingCount)
    }

    @Test fun mvxF02StateLatestDoesNotCollapseOrderedCommands() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val states = mutableListOf<Int>()
        val commands = mutableListOf<Int>()
        repeat(300) { value -> scheduler.submitState("title") { states += value } }
        scheduler.submitCommand { commands += 1 }
        scheduler.submitCommand { commands += 1 }
        scheduler.submitCommand { commands += 2 }
        driver.tick()
        assertEquals(listOf(299), states)
        assertEquals(listOf(1, 1, 2), commands)
        assertEquals(299L, scheduler.counters.coalescedStates)
        assertEquals(4L, scheduler.counters.appliedOperations)
        assertEquals(303L, scheduler.counters.releasedOperations)
    }

    @Test fun mvxF03OverloadRejectsNewSlotsButAllowsReplacement() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, stateCapacity = 1, commandCapacity = 2)
        val log = mutableListOf<String>()
        assertEquals(SubmitResult.Accepted, scheduler.submitState("a") { log += "old" })
        assertEquals(SubmitResult.Coalesced, scheduler.submitState("a") { log += "new" })
        assertEquals(SubmitResult.Rejected(RejectionReason.STATE_CAPACITY), scheduler.submitState("b") {})
        scheduler.submitCommand { log += "one" }
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), scheduler.submitBatch(listOf(
            ScheduledOperation { log += "never1" }, ScheduledOperation { log += "never2" },
        )))
        scheduler.submitCommand { log += "two" }
        driver.tick()
        assertEquals(listOf("new", "one", "two"), log)
    }

    @Test fun mvxF03ReentrantSubmissionsWaitForNextFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<String>()
        scheduler.submitCommand {
            log += "outer"
            scheduler.submitCommand { log += "inner" }
            scheduler.submitState("new") { log += "state" }
        }
        driver.tick()
        assertEquals(listOf("outer"), log)
        assertEquals(1, driver.pendingCount)
        driver.tick()
        assertEquals(setOf("outer", "inner", "state"), log.toSet())
        assertEquals(3, log.size)
    }

    @Test fun capacityIncludesUnprocessedFrameSnapshotDuringReentry() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, commandCapacity = 2)
        var result: SubmitResult? = null
        scheduler.submitCommand {
            result = scheduler.submitBatch(listOf(ScheduledOperation {}, ScheduledOperation {}))
        }
        scheduler.submitCommand {}
        driver.tick()
        assertEquals(SubmitResult.Rejected(RejectionReason.COMMAND_CAPACITY), result)
    }

    @Test fun orderedBatchUsesFifoAcrossFramesAndRecordsPartialFailure() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitBatch(listOf(
            ScheduledOperation("set") { log += "set" },
            ScheduledOperation("focus") { log += "focus" },
            ScheduledOperation("fail") { error("setter rejected") },
            ScheduledOperation("after") { log += "after" },
        ))
        scheduler.submitCommand { log += "later" }
        driver.tick()
        driver.tick()
        driver.tick()
        assertEquals(listOf("set", "focus"), log)
        assertEquals(SchedulerState.FAULTED, scheduler.state)
        assertEquals(2, scheduler.failure!!.operationIndex)
        assertEquals(2, scheduler.failure!!.appliedInBatch)
        assertEquals("fail", scheduler.failure!!.operation)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(0, driver.pendingCount)
        assertEquals(SubmitResult.Rejected(RejectionReason.FAULTED), scheduler.submitCommand {})
        scheduler.recover()
        scheduler.submitCommand { log += "reconciled" }
        driver.tick()
        assertEquals(listOf("set", "focus", "reconciled"), log)
    }

    @Test fun staleTokenDoesNotWriteAndValidationFailureFaults() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        var writes = 0
        scheduler.submitState("stale", isValid = { false }) { writes++ }
        scheduler.submitCommand(isValid = { error("token check failed") }) { writes++ }
        driver.tick()
        assertEquals(0, writes)
        assertEquals(1L, scheduler.counters.staleOperations)
        assertEquals("validation", scheduler.failure!!.phase)
        assertEquals(SchedulerState.FAULTED, scheduler.state)
    }

    @Test fun closeDuringActionPreventsAnyLaterCommitAndRejectsReentry() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<String>()
        var afterClose: SubmitResult? = null
        scheduler.submitCommand {
            log += "entered"
            scheduler.close()
            afterClose = scheduler.submitCommand { log += "reentrant" }
            log += "existing-callback-finished"
        }
        scheduler.submitCommand { log += "never" }
        driver.tick()
        assertEquals(listOf("entered", "existing-callback-finished"), log)
        assertEquals(SubmitResult.Rejected(RejectionReason.CLOSED), afterClose)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(0, driver.pendingCount)
        scheduler.close()
    }

    @Test fun closeDuringValidationPreventsAction() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        var writes = 0
        scheduler.submitCommand(isValid = { scheduler.close(); true }) { writes++ }
        driver.tick()
        assertEquals(0, writes)
    }

    @Test fun cancelInvalidatesBeforeBestEffortPhysicalRemoval() {
        var delivered: ((FrameBudget) -> Unit)? = null
        var cancelled = false
        val driver = object : UiFrameDriver {
            override val owner = "test.racing"
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                delivered = callback
                return FrameTicket { cancelled = true }
            }
        }
        val scheduler = UiScheduler(driver, {}, nanoTime = { 0 })
        var writes = 0
        scheduler.submitCommand { writes++ }
        scheduler.close()
        delivered!!(FrameBudget(0))
        assertTrue(cancelled)
        assertEquals(0, writes)
        assertEquals(1L, scheduler.counters.releasedOperations)
    }

    @Test fun pauseClearsPendingAndResumingDoesNotReplayIt() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        var writes = 0
        scheduler.submitCommand { writes++ }
        scheduler.pause()
        assertEquals(SubmitResult.Rejected(RejectionReason.PAUSED), scheduler.submitCommand {})
        assertEquals(0, driver.pendingCount)
        scheduler.resume()
        driver.tick()
        assertEquals(0, writes)
    }

    @Test fun explicitRetainingPauseResumesTheOriginalFifo() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        val log = mutableListOf<Int>()
        scheduler.submitCommand { log += 1; scheduler.pause(clearPending = false) }
        scheduler.submitCommand { log += 2 }
        driver.tick()
        assertEquals(listOf(1), log)
        scheduler.resume()
        driver.tick()
        assertEquals(listOf(1, 2), log)
    }

    @Test fun countBudgetAlternatesChannelsWithoutReorderingCommands() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitState("a") { log += "s1" }
        scheduler.submitState("b") { log += "s2" }
        scheduler.submitCommand { log += "c1" }
        scheduler.submitCommand { log += "c2" }
        repeat(4) { driver.tick() }
        assertEquals(listOf("s1", "c1", "s2", "c2"), log)
    }

    @Test fun expiredDeadlineAndElapsedBudgetStopAtOperationBoundaries() {
        val driver = VirtualFrameDriver()
        var time = 10L
        val scheduler = scheduler(driver, clock = { time })
        val log = mutableListOf<Int>()
        scheduler.submitCommand { log += 1; time += 2_000_001 }
        scheduler.submitCommand { log += 2 }
        driver.tick(FrameBudget(0, deadlineNanos = 5, estimated = false))
        assertTrue(log.isEmpty())
        driver.tick()
        assertEquals(listOf(1), log)
        driver.tick()
        assertEquals(listOf(1, 2), log)
    }

    @Test fun pendingReentrantStateSupersedesUnprocessedOldValueNextFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        scheduler.submitState("trigger") { scheduler.submitState("target") { log += "new" } }
        scheduler.submitState("target") { log += "old" }
        driver.tick()
        driver.tick()
        assertEquals(listOf("new"), log)
        assertEquals(1L, scheduler.counters.coalescedStates)
    }

    @Test fun virtualDriverCancelReleasesAndReentryWaitsForAnotherTick() {
        val driver = VirtualFrameDriver()
        val log = mutableListOf<Int>()
        driver.requestFrame { log += 9 }.cancel()
        driver.requestFrame {
            log += 1
            driver.requestFrame { log += 2 }
        }
        driver.tick()
        assertEquals(listOf(1), log)
        assertEquals(1, driver.pendingCount)
        driver.tick()
        assertEquals(listOf(1, 2), log)
        assertEquals(listOf("request:1", "cancel:1", "request:2", "tick:2", "request:3", "tick:3"), driver.events)
    }

    @Test fun inlineDriverDeliveryIsAnObservableFault() {
        val driver = object : UiFrameDriver {
            override val owner = "test.invalid-inline"
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket {
                callback(FrameBudget(0))
                return FrameTicket {}
            }
        }
        val scheduler = UiScheduler(driver, {})
        var writes = 0
        scheduler.submitCommand { writes++ }
        assertEquals(0, writes)
        assertEquals(SchedulerState.FAULTED, scheduler.state)
        assertEquals("driver", scheduler.failure!!.phase)
    }

    @Test fun ownerCheckIsAppliedToSubmissionAndClose() {
        val driver = VirtualFrameDriver()
        var owner = true
        val scheduler = UiScheduler(driver, { check(owner) { "wrong owner" } })
        owner = false
        assertThrows(IllegalStateException::class.java) { scheduler.submitCommand {} }
        assertThrows(IllegalStateException::class.java) { scheduler.close() }
        assertEquals(0, driver.pendingCount)
    }

    @Test fun stateGroupsRoundRobinEvenWhenOneBindingHasManyProperties() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, maxOperations = 1)
        val log = mutableListOf<String>()
        repeat(4) { index -> scheduler.submitState(StateSlot("a", index)) { log += "a$index" } }
        scheduler.submitState(StateSlot("b", 0)) { log += "b" }
        scheduler.submitState(StateSlot("c", 0)) { log += "c" }
        repeat(3) { driver.tick() }
        assertEquals(listOf("a0", "b", "c"), log)
    }

    @Test fun discardInvalidReleasesClosureCapacityAndCancelsEmptyFrame() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver, stateCapacity = 1, commandCapacity = 2)
        var valid = true
        scheduler.submitState("a", { valid }) { fail("stale state applied") }
        scheduler.submitBatch(listOf(
            ScheduledOperation("a", { valid }) { fail("stale batch applied") },
            ScheduledOperation("b", { valid }) { fail("stale batch applied") },
        ))
        valid = false
        scheduler.discardInvalid()
        assertEquals(0, scheduler.pendingStateCount)
        assertEquals(0, scheduler.pendingCommandCount)
        assertEquals(0, driver.pendingCount)
        assertEquals(3L, scheduler.counters.staleOperations)
        assertEquals(3L, scheduler.counters.releasedOperations)
    }

    @Test fun discardFromInsideCallbackDoesNotExecuteOrRetainInvalidSnapshot() {
        val driver = VirtualFrameDriver()
        val scheduler = scheduler(driver)
        var valid = true
        scheduler.submitCommand { valid = false; scheduler.discardInvalid() }
        scheduler.submitCommand({ valid }) { fail("discarded operation ran") }
        driver.tick()
        assertEquals(1L, scheduler.counters.appliedOperations)
        assertEquals(1L, scheduler.counters.staleOperations)
        assertEquals(0, scheduler.pendingCommandCount)
    }
}
