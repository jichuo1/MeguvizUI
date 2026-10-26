package io.github.jichuo1.meguviz.ui.scheduler

import java.util.ArrayDeque

enum class SchedulerState { ACTIVE, PAUSED, FAULTED, CLOSED }

enum class RejectionReason { PAUSED, FAULTED, CLOSED, STATE_CAPACITY, COMMAND_CAPACITY, EMPTY_BATCH }

/** Stable owner and property identities provide round-robin fairness between active bindings. */
data class StateSlot(val binding: Any, val property: Any)

sealed class SubmitResult {
    data object Accepted : SubmitResult()
    data object Coalesced : SubmitResult()
    data class Rejected(val reason: RejectionReason) : SubmitResult()
}

/** An explicitly ordered operation; [isValid] is rechecked immediately before its action. */
class ScheduledOperation(
    val label: String = "operation",
    val isValid: () -> Boolean = { true },
    val action: () -> Unit,
)

data class SchedulerFailure(
    val operation: String,
    val operationIndex: Int,
    val appliedInBatch: Int,
    val phase: String,
    val exceptionType: String,
    val message: String?,
)

data class SchedulerCounters(
    val acceptedSubmissions: Long,
    val coalescedStates: Long,
    val appliedOperations: Long,
    val staleOperations: Long,
    val releasedOperations: Long,
    val rejectedSubmissions: Long,
    val requestedFrames: Long,
    val drainedFrames: Long,
)

/**
 * Owner-thread-only bounded scheduler. States coalesce by slot; commands and batches use FIFO.
 * Channels have no total order. Reentrant work never enters the current frame snapshot.
 */
class UiScheduler(
    driver: UiFrameDriver,
    private val assertOwner: () -> Unit,
    val stateCapacity: Int = 256,
    val commandCapacity: Int = 256,
    val maxOperationsPerFrame: Int = 64,
    val maxFrameWorkNanos: Long = 2_000_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class IndexedOperation(val index: Int, val operation: ScheduledOperation)
    private class Batch(operations: List<ScheduledOperation>) {
        val operations = ArrayDeque(operations.mapIndexed(::IndexedOperation))
        var applied = 0
        val remaining: Int get() = operations.size
    }

    private var states = linkedMapOf<Any, ScheduledOperation>()
    private var driver: UiFrameDriver? = driver
    private var commands = ArrayDeque<Batch>()
    private var frameStates: LinkedHashMap<Any, ScheduledOperation>? = null
    private var frameCommands: ArrayDeque<Batch>? = null
    private var frameGroups: ArrayDeque<Any>? = null
    private var nextStateGroups = ArrayDeque<Any>()
    private var pendingTicket: FrameTicket? = null
    private var frameIdentity: Any? = null
    private var requestingFrame = false
    private var draining = false
    private var preferState = true
    private var accepted = 0L
    private var coalesced = 0L
    private var applied = 0L
    private var stale = 0L
    private var released = 0L
    private var rejected = 0L
    private var requested = 0L
    private var drained = 0L

    val driverOwner: String = checkNotNull(driver).owner
    var state: SchedulerState = SchedulerState.ACTIVE
        private set
    var failure: SchedulerFailure? = null
        private set
    var cancellationFailure: String? = null
        private set

    init {
        assertOwner()
        require(driverOwner.isNotBlank()) { "A frame driver must declare its owner" }
        require(stateCapacity > 0 && commandCapacity > 0)
        require(maxOperationsPerFrame > 0 && maxFrameWorkNanos > 0)
    }

    val pendingStateCount: Int
        get() {
            assertOwner()
            val active = frameStates ?: return states.size
            return active.size + states.keys.count { !active.containsKey(it) }
        }

    val pendingCommandCount: Int
        get() {
            assertOwner()
            return commands.sumOf { it.remaining } + (frameCommands?.sumOf { it.remaining } ?: 0)
        }

    val counters: SchedulerCounters
        get() {
            assertOwner()
            return SchedulerCounters(accepted, coalesced, applied, stale, released, rejected, requested, drained)
        }

    fun submitState(slot: Any, isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        val replacing = states.containsKey(slot)
        if (!replacing && frameStates?.containsKey(slot) != true && pendingStateCount >= stateCapacity) {
            return reject(RejectionReason.STATE_CAPACITY)
        }
        val previous = states.put(slot, ScheduledOperation("state", isValid, action))
        accepted++
        if (previous != null) {
            coalesced++
            released++
        }
        requestIfNeeded()
        return if (replacing) SubmitResult.Coalesced else SubmitResult.Accepted
    }

    fun submitCommand(isValid: () -> Boolean = { true }, action: () -> Unit): SubmitResult =
        submitBatch(listOf(ScheduledOperation("command", isValid, action)))

    /** Entire batch admission is atomic; applying the admitted batch is deliberately not a transaction. */
    fun submitBatch(operations: List<ScheduledOperation>): SubmitResult {
        assertOwner()
        rejectionForState()?.let { return reject(it) }
        if (operations.isEmpty()) return reject(RejectionReason.EMPTY_BATCH)
        if (operations.size > commandCapacity - pendingCommandCount) return reject(RejectionReason.COMMAND_CAPACITY)
        commands.addLast(Batch(operations.toList()))
        accepted++
        requestIfNeeded()
        return SubmitResult.Accepted
    }

    /** Pause rejects new submissions. Default detach behavior also releases queued closures. */
    fun pause(clearPending: Boolean = true) {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        state = SchedulerState.PAUSED
        cancelFrame()
        if (clearPending) clearQueues()
    }

    fun resume() {
        assertOwner()
        check(state == SchedulerState.PAUSED) { "Only a paused scheduler can resume" }
        state = SchedulerState.ACTIVE
        requestIfNeeded()
    }

    /** Caller must reconcile or rebuild actual state first. Failed operations are never retried. */
    fun recover() {
        assertOwner()
        check(state == SchedulerState.FAULTED) { "Only a faulted scheduler can recover" }
        failure = null
        state = SchedulerState.ACTIVE
    }

    /** Physically releases invalid pending work after rebind/release, without executing actions. */
    fun discardInvalid() {
        assertOwner()
        if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
        val stateQueues = listOfNotNull(states, frameStates)
        for (queue in stateQueues) {
            for ((slot, operation) in queue.toMap()) {
                if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                val valid = checkPending(operation, 0, 0) ?: return
                if (!valid && queue[slot] === operation) {
                    queue.remove(slot)
                    stale++
                    released++
                }
            }
        }
        for (queue in listOfNotNull(commands, frameCommands)) {
            for (batch in queue.toList()) {
                for (item in batch.operations.toList()) {
                    if (state == SchedulerState.CLOSED || state == SchedulerState.FAULTED) return
                    val valid = checkPending(item.operation, item.index, batch.applied) ?: return
                    if (!valid && queue.contains(batch) && batch.operations.remove(item)) {
                        stale++
                        released++
                    }
                }
                if (batch.remaining == 0) queue.remove(batch)
            }
        }
        val groups = (states.keys + (frameStates?.keys ?: emptySet())).map(::stateGroup).toSet()
        nextStateGroups.removeAll { it !in groups }
        frameGroups?.removeAll { it !in groups }
        if (pendingStateCount == 0 && pendingCommandCount == 0) cancelFrame()
    }

    private fun checkPending(operation: ScheduledOperation, index: Int, appliedInBatch: Int): Boolean? = try {
        operation.isValid()
    } catch (error: Throwable) {
        fault(operation.label, index, appliedInBatch, "validation", error)
        if (error is Error) throw error
        null
    }

    override fun close() {
        assertOwner()
        if (state == SchedulerState.CLOSED) return
        state = SchedulerState.CLOSED
        try { cancelFrame() } finally {
            clearQueues()
            driver = null
        }
    }

    private fun rejectionForState(): RejectionReason? = when (state) {
        SchedulerState.ACTIVE -> null
        SchedulerState.PAUSED -> RejectionReason.PAUSED
        SchedulerState.FAULTED -> RejectionReason.FAULTED
        SchedulerState.CLOSED -> RejectionReason.CLOSED
    }

    private fun reject(reason: RejectionReason): SubmitResult.Rejected {
        rejected++
        return SubmitResult.Rejected(reason)
    }

    private fun requestIfNeeded() {
        if (state != SchedulerState.ACTIVE || draining || frameIdentity != null ||
            (states.isEmpty() && commands.isEmpty())) return
        val identity = Any()
        frameIdentity = identity
        requestingFrame = true
        requested++
        try {
            val ticket = checkNotNull(driver).requestFrame { budget ->
                assertOwner()
                check(!requestingFrame) { "UiFrameDriver must not invoke a requested callback synchronously" }
                if (frameIdentity !== identity || state != SchedulerState.ACTIVE) return@requestFrame
                frameIdentity = null
                pendingTicket = null
                drain(budget)
            }
            if (frameIdentity === identity) pendingTicket = ticket else ticket.cancel()
        } catch (error: Throwable) {
            fault("frame-request", -1, 0, "driver", error)
            if (error is Error) throw error
        } finally {
            requestingFrame = false
        }
    }

    private fun drain(budget: FrameBudget) {
        check(!draining) { "Nested frame delivery is not supported" }
        draining = true
        drained++
        frameStates = states
        frameCommands = commands
        val groups = states.keys.map(::stateGroup).distinct()
        val retainedOrder = nextStateGroups.filter { it in groups }
        frameGroups = ArrayDeque(retainedOrder + groups.filter { it !in retainedOrder })
        nextStateGroups.clear()
        states = linkedMapOf()
        commands = ArrayDeque()
        val started = nanoTime()
        var operationCount = 0
        try {
            while (state == SchedulerState.ACTIVE && operationCount < maxOperationsPerFrame) {
                val now = nanoTime()
                if (now - started >= maxFrameWorkNanos || budget.deadlineNanos?.let { now >= it } == true) break
                val stateWork = frameStates!!.isNotEmpty()
                val commandWork = frameCommands!!.isNotEmpty()
                if (!stateWork && !commandWork) break
                val takeState = stateWork && (!commandWork || preferState)
                preferState = !takeState
                if (takeState) applyState() else applyCommand()
                operationCount++
            }
        } finally {
            if (state == SchedulerState.ACTIVE || state == SchedulerState.PAUSED) {
                val remainingStates = frameStates!!
                states.forEach { (key, operation) ->
                    if (remainingStates.put(key, operation) != null) {
                        coalesced++
                        released++
                    }
                }
                states = remainingStates
                val remainingGroups = states.keys.map(::stateGroup).distinct()
                val retainedGroups = frameGroups!!.filter { it in remainingGroups }
                nextStateGroups = ArrayDeque(retainedGroups + remainingGroups.filter { it !in retainedGroups })
                val remainingCommands = frameCommands!!
                remainingCommands.addAll(commands)
                commands = remainingCommands
            }
            frameStates = null
            frameCommands = null
            frameGroups = null
            draining = false
            requestIfNeeded()
        }
    }

    private fun applyState() {
        val queue = frameStates!!
        val groups = frameGroups!!
        while (groups.isNotEmpty()) {
            val group = groups.removeFirst()
            val slot = queue.keys.firstOrNull { stateGroup(it) == group } ?: continue
            val operation = queue.remove(slot)!!
            if (queue.keys.any { stateGroup(it) == group }) groups.addLast(group)
            apply(operation, 0, 0)
            return
        }
    }

    private fun stateGroup(slot: Any): Any = if (slot is StateSlot) slot.binding else slot

    private fun applyCommand() {
        val queue = frameCommands!!
        val batch = queue.first
        val item = batch.operations.removeFirst()
        if (batch.remaining == 0) queue.removeFirst()
        if (apply(item.operation, item.index, batch.applied)) batch.applied++
    }

    private fun apply(operation: ScheduledOperation, index: Int, appliedInBatch: Int): Boolean {
        var phase = "validation"
        return try {
            if (!operation.isValid()) {
                stale++
                false
            } else if (state != SchedulerState.ACTIVE) {
                false
            } else {
                phase = "action"
                operation.action()
                applied++
                true
            }
        } catch (error: Throwable) {
            fault(operation.label, index, appliedInBatch, phase, error)
            if (error is Error) throw error
            false
        } finally {
            released++
        }
    }

    private fun fault(label: String, index: Int, appliedInBatch: Int, phase: String, error: Throwable) {
        failure = SchedulerFailure(label, index, appliedInBatch, phase, error.javaClass.name, error.message)
        if (state != SchedulerState.CLOSED) state = SchedulerState.FAULTED
        cancelFrame()
        clearQueues()
    }

    private fun cancelFrame() {
        frameIdentity = null
        val ticket = pendingTicket
        pendingTicket = null
        try {
            ticket?.cancel()
        } catch (error: Exception) {
            cancellationFailure = "${error.javaClass.name}: ${error.message}"
        }
    }

    private fun clearQueues() {
        released += states.size + commands.sumOf { it.remaining }
        released += (frameStates?.size ?: 0) + (frameCommands?.sumOf { it.remaining } ?: 0)
        states.clear()
        commands.clear()
        frameStates?.clear()
        frameCommands?.clear()
        frameGroups?.clear()
        nextStateGroups.clear()
    }
}
