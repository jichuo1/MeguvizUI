package io.github.jichuo1.meguviz.ui.prewarm

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.Choreographer
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the platform frame driver; no virtual clock or host View is used. */
class PrewarmInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val cases = linkedMapOf<String, () -> Unit>(
            "platform_one_unit_per_frame" to ::oneUnitPerFrame,
            "cancel_before_frame_releases_work" to ::cancelBeforeFrame,
        )
        var failures = 0
        cases.entries.forEachIndexed { index, (name, body) ->
            fun status() = Bundle().apply {
                putString("class", PrewarmInstrumentation::class.java.name)
                putString("test", name)
                putInt("numtests", cases.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status())
            var failure: Throwable? = null
            try { body() } catch (error: Throwable) { failure = error }
            val end = status()
            failure?.let { failures++; end.putString("stack", it.stackTraceToString()) }
            sendStatus(if (failure == null) 0 else -2, end)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "Prewarm: ${cases.size} tests, $failures failures\n") })
    }

    private fun oneUnitPerFrame() {
        val finished = CountDownLatch(1)
        val frames = mutableListOf<Long>()
        var currentFrame = 0L
        var discarded: PrewarmStop? = null
        var session: UiSession? = null
        var observer: Choreographer.FrameCallback? = null
        try {
            runOnMainSync {
                val active = UiSession(CreationEnvironment(targetContext))
                session = active
                val choreographer = Choreographer.getInstance()
                val frameObserver = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        currentFrame = frameTimeNanos
                        choreographer.postFrameCallback(this)
                    }
                }
                observer = frameObserver
                choreographer.postFrameCallback(frameObserver)
                val prewarmer = UiPrewarmer.attach(active, maxUnitNanos = Long.MAX_VALUE)
                val result = prewarmer.submit(PrewarmWork(
                    isCurrent = { true },
                    step = { frames += currentFrame; frames.size == 3 },
                    ready = { finished.countDown() },
                    discard = { reason -> discarded = reason; finished.countDown() },
                ))
                check(result == PrewarmSubmission.Accepted)
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Frame work timed out" }
            check(discarded == null && frames.size == 3 && frames.first() > 0 && frames.zipWithNext().all { (a, b) -> b > a }) {
                "Work did not complete in three distinct platform frames"
            }
        } finally {
            runOnMainSync {
                observer?.let { Choreographer.getInstance().removeFrameCallback(it) }
                session?.close()
            }
        }
    }

    private fun cancelBeforeFrame() {
        runOnMainSync {
            val session = UiSession(CreationEnvironment(targetContext))
            try {
                val prewarmer = UiPrewarmer.attach(session)
                var ran = false
                var discarded: PrewarmStop? = null
                check(prewarmer.submit(PrewarmWork({ true }, { ran = true; true }, {}, { discarded = it })) == PrewarmSubmission.Accepted)
                prewarmer.cancel()
                check(!ran && discarded == PrewarmStop.CANCELLED && prewarmer.pendingCount == 0)
            } finally { session.close() }
        }
    }
}
