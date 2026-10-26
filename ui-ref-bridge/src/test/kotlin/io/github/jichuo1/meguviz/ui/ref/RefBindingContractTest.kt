package io.github.jichuo1.meguviz.ui.ref

import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec
import io.github.jichuo1.meguviz.ref.api.result.ResolutionException
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus
import io.github.jichuo1.meguviz.ref.runtime.invoke.StalePlanException
import io.github.jichuo1.meguviz.ref.scope.CodeScope
import io.github.jichuo1.meguviz.ui.scheduler.UiScheduler
import io.github.jichuo1.meguviz.ui.testkit.VirtualFrameDriver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RefBindingContractTest {
    class Receiver {
        var calls = 0
        val values = mutableListOf<Int>()
        fun add(value: Int): Int { calls++; values += value; return value }
        fun fail(): Nothing { calls++; throw IllegalArgumentException("target failure") }
        fun overload(value: Int) = value
        fun overload(value: String) = value
        private fun secret() = 42
    }

    private fun scope() = CodeScope.open(Receiver::class.java.classLoader)
    private fun query(name: String, vararg parameters: Class<*>): QuerySpec =
        QuerySpec.builder(Receiver::class.java, MemberKind.METHOD).name(name).parameters(*parameters).build()
    private fun session(scope: CodeScope, invalidated: () -> Unit = {}): RefBindingSession {
        val owner = Thread.currentThread()
        return RefBindingSession(scope, { check(Thread.currentThread() === owner) }, invalidated = invalidated)
    }

    @Test fun closingOneUiBindingDoesNotCloseSharedCodeScopeOrSecondBinding() {
        scope().use { code ->
            val first = session(code)
            val second = session(code)
            val one = first.receiver(Receiver()).method(query("add", Integer.TYPE))
            val two = second.receiver(Receiver()).method(query("add", Integer.TYPE))
            first.close()
            assertFalse(one.isCurrent())
            assertEquals(CodeScope.State.OPEN, code.state())
            assertEquals(7, two.invoke(7))
            second.close()
        }
    }

    @Test fun codeEpochChangeRejectsAlreadyResolvedCallsAndPendingOperations() {
        scope().use { code -> session(code).use { binding ->
            val receiver = Receiver()
            val method = binding.receiver(receiver).method(query("add", Integer.TYPE))
            val operation = method.operation("late", 4)
            code.advanceCodeEpoch("replacement")
            assertFalse(operation.isValid())
            assertThrows(StalePlanException::class.java) { method.invoke(4) }
            assertEquals(0, receiver.calls)
        } }
    }

    @Test fun closedCodeScopeIsStaleAndBridgeCloseReleasesItsLocalReceiver() {
        val code = scope()
        val binding = session(code)
        val lease = binding.receiver(Receiver())
        val method = lease.method(query("add", Integer.TYPE))
        code.close()
        assertFalse(lease.isCurrent())
        assertThrows(StalePlanException::class.java) { method.invoke(1) }
        binding.close()
        assertFalse(lease.retainsReceiver)
        assertEquals(0, binding.retainedHandleCount)
    }

    @Test fun rebindingTokenInvalidatesAnOtherwiseOpenCodePlan() {
        scope().use { code -> session(code).use { binding ->
            var current = true
            val receiver = Receiver()
            val lease = binding.receiver(receiver) { current }
            val method = lease.method(query("add", Integer.TYPE))
            current = false
            assertFalse(method.isCurrent())
            assertThrows(IllegalStateException::class.java) { method.invoke(1) }
            assertEquals(0, receiver.calls)
            assertEquals(CodeScope.State.OPEN, code.state())
        } }
    }

    @Test fun stableCallsDoNotRepeatResolutionOrCandidateTraversal() {
        scope().use { code -> session(code).use { binding ->
            val receiver = Receiver()
            val method = binding.receiver(receiver).method(query("add", Integer.TYPE))
            val before = code.diagnostics()
            repeat(10_000) { assertEquals(it, method.invoke(it)) }
            assertEquals(10_000, receiver.calls)
            assertEquals(before["candidateChecks"], code.diagnostics()["candidateChecks"])
            assertEquals(before["lookups"], code.diagnostics()["lookups"])
        } }
    }

    @Test fun ambiguousResolutionKeepsTheUpstreamStructuredStatus() {
        scope().use { code -> session(code).use { binding ->
            val lease = binding.receiver(Receiver())
            val ambiguous = QuerySpec.builder(Receiver::class.java, MemberKind.METHOD).name("overload").build()
            val failure = assertThrows(ResolutionException::class.java) { lease.method(ambiguous) }
            assertEquals(ResolutionStatus.AMBIGUOUS, failure.status())
        } }
    }

    @Test fun missingMemberIsOnlyMissingInTheVisibleUniverse() {
        scope().use { code -> session(code).use { binding ->
            val failure = assertThrows(ResolutionException::class.java) {
                binding.receiver(Receiver()).method(query("doesNotExist"))
            }
            assertEquals(ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE, failure.status())
        } }
    }

    @Test fun inaccessibleMembersRemainAccessErrorsRatherThanNotFound() {
        scope().use { code -> session(code).use { binding ->
            val method = binding.receiver(Receiver()).method(query("secret"))
            assertThrows(IllegalAccessException::class.java) { method.invoke() }
        } }
    }

    @Test fun targetExceptionAndInvocationCountArePreservedWithoutRetry() {
        scope().use { code -> session(code).use { binding ->
            val receiver = Receiver()
            val method = binding.receiver(receiver).method(query("fail"))
            val failure = assertThrows(InvocationTargetException::class.java) { method.invoke() }
            assertTrue(failure.targetException is IllegalArgumentException)
            assertEquals("target failure", failure.targetException.message)
            assertEquals(1, receiver.calls)
        } }
    }

    @Test fun orderedCommandsKeepBothCallsAndCopyTheArgumentArray() {
        scope().use { code ->
            val driver = VirtualFrameDriver()
            val scheduler = UiScheduler(driver, {}, maxFrameWorkNanos = Long.MAX_VALUE)
            session(code, scheduler::discardInvalid).use { binding ->
                val receiver = Receiver()
                val method = binding.receiver(receiver).method(query("add", Integer.TYPE))
                val values = arrayOf<Any?>(1)
                val first = method.operation("first", *values)
                values[0] = 99
                scheduler.submitBatch(listOf(first, method.operation("second", 2)))
                driver.tick()
                assertEquals(listOf(1, 2), receiver.values)
            }
            scheduler.close()
        }
    }

    @Test fun closingLeaseImmediatelyRemovesItsQueuedCallsFromTheOwningScheduler() {
        scope().use { code ->
            val driver = VirtualFrameDriver()
            val scheduler = UiScheduler(driver, {})
            session(code, scheduler::discardInvalid).use { binding ->
                val receiver = Receiver()
                val lease = binding.receiver(receiver)
                val method = lease.method(query("add", Integer.TYPE))
                scheduler.submitBatch(listOf(method.operation("pending", 2)))
                assertEquals(1, scheduler.pendingCommandCount)
                lease.close()
                assertFalse(lease.retainsReceiver)
                assertEquals(0, scheduler.pendingCommandCount)
                assertEquals(0, driver.pendingCount)
                assertEquals(0, receiver.calls)
            }
            scheduler.close()
        }
    }

    @Test fun closingMethodAlsoInvalidatesOnlyItsPendingCommands() {
        scope().use { code ->
            val driver = VirtualFrameDriver()
            val scheduler = UiScheduler(driver, {})
            session(code, scheduler::discardInvalid).use { binding ->
                val lease = binding.receiver(Receiver())
                val method = lease.method(query("add", Integer.TYPE))
                scheduler.submitBatch(listOf(method.operation("pending", 2)))
                method.close()
                assertEquals(0, scheduler.pendingCommandCount)
                assertTrue(lease.isCurrent())
                assertEquals(3, lease.method(query("add", Integer.TYPE)).invoke(3))
            }
            scheduler.close()
        }
    }

    @Test fun nonOwnerThreadCannotInvokeOrCloseALiveLease() {
        scope().use { code -> session(code).use { binding ->
            val receiver = Receiver()
            val lease = binding.receiver(receiver)
            val method = lease.method(query("add", Integer.TYPE))
            val failures = AtomicReference<List<Throwable>>()
            Thread {
                failures.set(listOfNotNull(runCatching { method.invoke(1) }.exceptionOrNull(), runCatching { lease.close() }.exceptionOrNull()))
            }.apply { start(); join() }
            assertEquals(2, failures.get().size)
            assertTrue(failures.get().all { it is IllegalStateException })
            assertEquals(0, receiver.calls)
            assertTrue(lease.retainsReceiver)
        } }
    }

    @Test fun reentrantCloseInValidityCheckCannotStartAControlledCall() {
        scope().use { code ->
            val binding = session(code)
            val receiver = Receiver()
            var closing = false
            val lease = binding.receiver(receiver) { if (closing) binding.close(); true }
            val method = lease.method(query("add", Integer.TYPE))
            closing = true
            assertFalse(method.isCurrent())
            assertThrows(IllegalStateException::class.java) { method.invoke(1) }
            assertEquals(0, receiver.calls)
            assertFalse(lease.retainsReceiver)
        }
    }

    @Test fun sameNamedReceiverClassesInDifferentLoadersKeepSeparateLeasesAndPlans() {
        val name = Receiver::class.java.name
        val bytes = checkNotNull(Receiver::class.java.getResourceAsStream("/${name.replace('.', '/')}.class")).use { it.readBytes() }
        fun loader() = object : ClassLoader(Receiver::class.java.classLoader) {
            override fun loadClass(className: String, resolve: Boolean): Class<*> {
                if (className != name) return super.loadClass(className, resolve)
                return synchronized(this) {
                    val type = findLoadedClass(className) ?: defineClass(className, bytes, 0, bytes.size)
                    if (resolve) resolveClass(type)
                    type
                }
            }
        }
        val firstLoader = loader(); val secondLoader = loader()
        val firstType = firstLoader.loadClass(name); val secondType = secondLoader.loadClass(name)
        assertNotSame(firstType, secondType)
        CodeScope.open(firstLoader).use { firstCode -> CodeScope.open(secondLoader).use { secondCode ->
            session(firstCode).use { first -> session(secondCode).use { second ->
                val one = first.receiver(firstType.getConstructor().newInstance()).method(
                    QuerySpec.builder(firstType, MemberKind.METHOD).name("add").parameters(Integer.TYPE).build())
                val two = second.receiver(secondType.getConstructor().newInstance()).method(
                    QuerySpec.builder(secondType, MemberKind.METHOD).name("add").parameters(Integer.TYPE).build())
                assertEquals(1, one.invoke(1)); assertEquals(2, two.invoke(2))
                firstCode.close()
                assertFalse(one.isCurrent())
                assertEquals(3, two.invoke(3))
            } }
        } }
    }
}
