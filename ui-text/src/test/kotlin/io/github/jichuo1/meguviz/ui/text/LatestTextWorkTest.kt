package io.github.jichuo1.meguviz.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class LatestTextWorkTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeFirst().run() }
        fun runBackground() {
            var failure: Throwable? = null
            val thread = Thread { try { run() } catch (error: Throwable) { failure = error } }
            thread.start(); thread.join(); failure?.let { throw it }
        }
    }

    @Test fun staleWorkIsDiscardedAndPendingIsBounded() {
        val background = Queue(); val owner = Queue(); val calculated = mutableListOf<Int>(); val applied = mutableListOf<Int>()
        val work = LatestTextWork(background, owner, { n: Int -> calculated += n; n }, { _, result -> applied += result.getOrThrow() })
        repeat(500) { work.submit(it) }
        assertEquals(1, background.tasks.size)
        background.runBackground(); owner.run()
        assertTrue(applied.isEmpty())
        background.runBackground(); owner.run()
        assertEquals(listOf(0, 499), calculated)
        assertEquals(listOf(499), applied)
    }

    @Test fun closeAndInvalidateDropPendingAndClosedCompletion() {
        val background = Queue(); val owner = Queue(); val applied = mutableListOf<Int>()
        val work = LatestTextWork(background, owner, { n: Int -> n }, { _, result -> applied += result.getOrThrow() })
        work.submit(1); work.submit(2); work.invalidate(); work.close()
        background.runBackground(); owner.run()
        assertTrue(applied.isEmpty()); assertTrue(background.tasks.isEmpty())
    }

    @Test fun closeBeforeOwnerDeliveryPreventsCommit() {
        val background = Queue(); val owner = Queue(); val applied = mutableListOf<Int>()
        val work = LatestTextWork(background, owner, { n: Int -> n }, { _, result -> applied += result.getOrThrow() })
        work.submit(1); background.runBackground(); work.close(); owner.run()
        assertTrue(applied.isEmpty())
    }

    @Test fun executorRejectionIsObservable() {
        val failures = mutableListOf<Throwable>()
        val work = LatestTextWork<Int, Int>(Executor { throw RejectedExecutionException() }, Queue(), { it }, { _, result -> result.exceptionOrNull()?.let(failures::add) })
        work.submit(1)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is RejectedExecutionException)
    }

    @Test fun ownerRejectionClearsWorkAndBlocksSubsequentSubmissions() {
        val background = Queue()
        val work = LatestTextWork<Int, Int>(background, Executor { throw RejectedExecutionException() }, { it }, { _, _ -> })
        work.submit(1); work.submit(2)
        background.runBackground()
        assertTrue(background.tasks.isEmpty())
        val error = assertThrows(IllegalStateException::class.java) { work.submit(3) }
        assertTrue(error.cause is RejectedExecutionException)
    }

    @Test fun synchronousBackgroundIsRejectedAndWrongOwnerThreadCloses() {
        val failures = mutableListOf<Throwable>()
        val inline = LatestTextWork<Int, Int>(Executor { it.run() }, Queue(), { it }, { _, result -> result.exceptionOrNull()?.let(failures::add) })
        inline.submit(1)
        assertEquals(1, failures.size)
        val background = Queue()
        val wrongOwner = LatestTextWork<Int, Int>(background, Executor { it.run() }, { it }, { _, _ -> })
        wrongOwner.submit(1)
        background.runBackground()
        assertThrows(IllegalStateException::class.java) { wrongOwner.submit(2) }
    }

    @Test fun delayedBackgroundOnOwnerThreadIsRejected() {
        val background = Queue()
        val work = LatestTextWork<Int, Int>(background, Queue(), { it }, { _, _ -> })
        work.submit(1)
        background.run()
        assertThrows(IllegalStateException::class.java) { work.submit(2) }
    }
}
