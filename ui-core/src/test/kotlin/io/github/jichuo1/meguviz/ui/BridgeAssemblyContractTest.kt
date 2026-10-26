package io.github.jichuo1.meguviz.ui

import io.github.jichuo1.meguviz.ui.scheduler.FrameBudget
import io.github.jichuo1.meguviz.ui.scheduler.FrameTicket
import io.github.jichuo1.meguviz.ui.scheduler.UiFrameDriver
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import org.junit.Assert.*
import org.junit.Test

/** UI-owned fake-backed assembly contracts, not certification of unavailable sibling APIs. */
class BridgeAssemblyContractTest {
    @Test fun injectedDriverDrainsInItsTickWithoutSecondFrameOrMailbox() {
        val upstream = VirtualFrameDriver("test.external-frame-source")
        var inUpstreamTick = false
        var writes = 0
        val adapter = object : UiFrameDriver {
            override val owner = upstream.owner
            override fun requestFrame(callback: (FrameBudget) -> Unit): FrameTicket = upstream.requestFrame { budget ->
                inUpstreamTick = true
                try { callback(budget.copy(source = owner)) } finally { inUpstreamTick = false }
            }
        }
        val scheduler = UiScheduler(adapter, {}, nanoTime = { 0 })
        scheduler.submitState("slot") { assertTrue(inUpstreamTick); writes++ }
        assertEquals(1, upstream.pendingCount)
        upstream.tick()
        assertEquals(1, writes)
        assertEquals(0, upstream.pendingCount)
        assertEquals(1, scheduler.counters.requestedFrames)
        scheduler.close()
    }

    @Test fun authorityUsesItsOwnDomainAndUiNeverAdvancesIt() {
        val external = EpochDomain()
        val unrelated = EpochDomain()
        val authority = ResourceAuthority(external::token) { it == external.token() }
        val token = authority.capture()
        unrelated.advance()
        assertTrue(authority.isCurrent(token))
        external.advance()
        assertFalse(authority.isCurrent(token))
    }

    @Test fun closingOneWindowSchedulerDoesNotCloseAnotherSharedSourceConsumer() {
        val driver = VirtualFrameDriver("test.shared-source")
        val first = UiScheduler(driver, {}, nanoTime = { 0 })
        val second = UiScheduler(driver, {}, nanoTime = { 0 })
        val log = mutableListOf<String>()
        first.submitCommand { log += "first" }
        second.submitCommand { log += "second" }
        first.close()
        driver.tick()
        assertEquals(listOf("second"), log)
        second.close()
        assertEquals(0, driver.pendingCount)
    }
}
