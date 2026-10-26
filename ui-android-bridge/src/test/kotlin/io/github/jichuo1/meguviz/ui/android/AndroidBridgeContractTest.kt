package io.github.jichuo1.meguviz.ui.android

import io.github.jichuo1.meguviz.android.runtime.core.FrameDriver
import io.github.jichuo1.meguviz.android.runtime.core.FrameScheduler
import io.github.jichuo1.meguviz.android.runtime.core.OwnerDispatcher
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceSnapshot
import io.github.jichuo1.meguviz.android.runtime.resources.ResourceState
import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.SchedulerState
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import org.junit.Assert.*
import org.junit.Test

/** Real upstream scheduler/resource classes; only the external platform clock is controlled. */
class AndroidBridgeContractTest {
    private class PlatformClock : OwnerDispatcher, FrameDriver {
        private val owner = Thread.currentThread()
        private var frame: FrameDriver.Callback? = null
        private var nanos = 0L
        var requests = 0
        var removals = 0
        val errors = mutableListOf<Throwable>()
        override fun isOwnerThread(): Boolean = Thread.currentThread() === owner
        override fun post(callback: Runnable): Boolean = error("Bridge must not post another owner event")
        override fun remove(callback: Runnable) = Unit
        override fun request(callback: FrameDriver.Callback): Boolean {
            checkOwner()
            check(frame == null) { "Duplicate physical frame source" }
            requests++
            frame = callback
            return true
        }
        override fun remove(callback: FrameDriver.Callback) {
            checkOwner()
            if (frame === callback) { frame = null; removals++ }
        }
        fun tick() {
            checkOwner()
            val callback = checkNotNull(frame)
            frame = null
            nanos += 16_666_667L
            callback.onFrame(nanos)
        }
        fun hasFrame(): Boolean = frame != null
        fun scheduler(): FrameScheduler = FrameScheduler(this, this, { errors += it }, 64)
    }

    @Test fun mvxF01UpstreamTickDrainsUiImmediatelyWithoutSecondFrameOrMailbox() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        val ui = UiScheduler(driver, clock::checkOwner, nanoTime = { 0 })
        val state = mutableListOf<Int>()
        val commands = mutableListOf<Int>()
        repeat(300) { value -> ui.submitState("title") { state += value } }
        ui.submitCommand { commands += 1 }
        ui.submitCommand { commands += 1 }
        assertEquals(1, clock.requests)
        assertEquals(1, upstream.channelCount())
        clock.tick()
        assertEquals(listOf(299), state)
        assertEquals(listOf(1, 1), commands)
        assertFalse(clock.hasFrame())
        assertEquals(1L, driver.lastFrameSequence)
        assertEquals(1L, ui.counters.drainedFrames)
        ui.close(); driver.close(); upstream.close()
    }

    @Test fun copiedBudgetRetainsUnknownDeadlineAndUpstreamSequence() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        var budget: FrameBudget? = null
        driver.requestFrame { budget = it }
        clock.tick()
        val copied = checkNotNull(budget)
        assertEquals(16_666_667L, copied.frameTimeNanos)
        assertNull(copied.deadlineNanos)
        assertTrue(copied.estimated)
        assertEquals("meguviz-android.runtime:unknown", copied.source)
        driver.close(); upstream.close()
    }

    @Test fun mvxF03ReentrantUiSubmissionUsesExactlyTheNextUpstreamFrame() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        val ui = UiScheduler(driver, clock::checkOwner, nanoTime = { 0 })
        val calls = mutableListOf<Int>()
        ui.submitCommand { calls += 1; ui.submitCommand { calls += 2 } }
        clock.tick()
        assertEquals(listOf(1), calls)
        assertEquals(2, clock.requests)
        clock.tick()
        assertEquals(listOf(1, 2), calls)
        assertFalse(clock.hasFrame())
        ui.close(); driver.close(); upstream.close()
    }

    @Test fun staleTicketCannotCancelAReplacementRequest() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        val old = driver.requestFrame { fail("Cancelled frame delivered") }
        old.cancel()
        var calls = 0
        driver.requestFrame { calls++ }
        old.cancel()
        clock.tick()
        assertEquals(1, calls)
        assertEquals(1, clock.removals)
        driver.close(); upstream.close()
    }

    @Test fun sameSessionIdentityCannotAcquireTwoUpstreamChannels() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val identity = Any()
        val first = AndroidFrameDriver(clock, upstream, identity)
        assertThrows(IllegalStateException::class.java) { AndroidFrameDriver(clock, upstream, identity) }
        assertEquals(1, upstream.channelCount())
        first.close()
        AndroidFrameDriver(clock, upstream, identity).close()
        assertEquals(0, upstream.channelCount())
        upstream.close()
    }

    @Test fun upstreamSchedulerCloseInvalidatesUiAndReleasesPendingCallbacks() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        val ui = UiScheduler(driver, clock::checkOwner, nanoTime = { 0 })
        var closed = 0
        driver.onClose { closed++; ui.close() }
        ui.submitCommand { fail("Closed session committed") }
        upstream.close()
        assertTrue(driver.isClosed)
        assertEquals(SchedulerState.CLOSED, ui.state)
        assertEquals(0, ui.pendingCommandCount)
        assertFalse(clock.hasFrame())
        driver.close(); upstream.close()
        assertEquals(1, closed)
    }

    @Test fun closingOneBorrowerKeepsTheSharedSchedulerAndOtherUiAlive() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val first = AndroidFrameDriver(clock, upstream, Any())
        val second = AndroidFrameDriver(clock, upstream, Any())
        first.requestFrame { fail("Closed borrower received a tick") }
        var secondCalls = 0
        second.requestFrame { secondCalls++ }
        first.close()
        assertEquals(1, upstream.channelCount())
        clock.tick()
        assertEquals(1, secondCalls)
        second.close(); upstream.close()
    }

    @Test fun closeDuringUiCallbackPreventsLaterLibraryCommit() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        val ui = UiScheduler(driver, clock::checkOwner, nanoTime = { 0 })
        driver.onClose(ui::close)
        val calls = mutableListOf<String>()
        ui.submitCommand { calls += "entered"; upstream.close(); calls += "existing callback completed" }
        ui.submitCommand { calls += "never" }
        clock.tick()
        assertEquals(listOf("entered", "existing callback completed"), calls)
        assertEquals(SchedulerState.CLOSED, ui.state)
        assertEquals(0, upstream.channelCount())
    }

    @Test fun resourceAuthorityUsesActualUpstreamDomainAndThemeInvalidation() {
        val clock = PlatformClock()
        val snapshot = ResourceSnapshot(320, 1f, "zh-CN", 0, 0, 360, 640, 0, "fixture")
        val first = ResourceState(clock, "window-one", snapshot, 4)
        val second = ResourceState(clock, "window-two", snapshot, 4)
        val authority = androidResourceAuthority(first)
        val other = androidResourceAuthority(second)
        val token = authority.capture()
        assertTrue(authority.isCurrent(token))
        assertFalse(other.isCurrent(token))
        assertFalse(authority.isCurrent(Any()))
        first.invalidateTheme()
        assertFalse(authority.isCurrent(token))
        val themed = authority.capture()
        first.invalidateResources()
        assertFalse(authority.isCurrent(themed))
        val fresh = authority.capture()
        first.close()
        assertFalse(authority.isCurrent(fresh))
        second.close()
    }

    @Test fun callbackFailureIsReportedByRealUpstreamAndClosesTheChannel() {
        val clock = PlatformClock()
        val upstream = clock.scheduler()
        val driver = AndroidFrameDriver(clock, upstream, Any())
        var closed = false
        driver.onClose { closed = true }
        driver.requestFrame { error("consumer failure") }
        clock.tick()
        assertTrue(closed)
        assertTrue(driver.isClosed)
        assertEquals(1, clock.errors.size)
        assertEquals("consumer failure", clock.errors.single().message)
        assertEquals(0, upstream.channelCount())
        upstream.close()
    }
}
