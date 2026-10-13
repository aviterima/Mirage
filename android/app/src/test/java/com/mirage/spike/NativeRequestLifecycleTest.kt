package com.mirage.spike

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRequestLifecycleTest {
    @Test fun cancellationBeforeRegistrationNeverStartsNativeWork() {
        val request = NativeRequestLifecycle<String>()
        val calls = AtomicInteger()
        request.cancel()
        assertThrows(CancellationException::class.java) {
            request.start(cancelNative = { calls.incrementAndGet() }) { calls.incrementAndGet() }
        }
        assertEquals(0, calls.get())
    }

    @Test fun cancellationDuringRegistrationStopsTheRegisteredRequest() {
        val request = NativeRequestLifecycle<String>()
        val registered = CountDownLatch(1)
        val finishRegistration = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val threads = Executors.newFixedThreadPool(2)
        try {
            val registration = threads.submit {
                request.start(cancelNative = { cancellations.incrementAndGet() }) {
                    registered.countDown()
                    check(finishRegistration.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(registered.await(5, TimeUnit.SECONDS))
            val cancellation = threads.submit {
                cancelling.countDown()
                request.cancel()
            }
            assertTrue(cancelling.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { cancellation.get(100, TimeUnit.MILLISECONDS) }
            finishRegistration.countDown()
            registration.get(5, TimeUnit.SECONDS)
            cancellation.get(5, TimeUnit.SECONDS)
            assertEquals(1, cancellations.get())
        } finally {
            finishRegistration.countDown()
            threads.shutdownNow()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellingDoesNotReleaseOwnerBeforeNativeTerminalCallback() = runTest {
        val request = NativeRequestLifecycle<String>()
        var stopped = false
        request.start(cancelNative = { stopped = true }) {}
        val result = async { request.await() }
        runCurrent()
        request.cancel()
        assertTrue(stopped)
        assertFalse(result.isCompleted)
        request.complete("terminal response")
        assertEquals("terminal response", result.await())
    }

    @Test fun aQueuedOrCompletedOwnerCannotCancelAnotherRequest() {
        val first = NativeRequestLifecycle<String>()
        val second = NativeRequestLifecycle<String>()
        val cancellations = AtomicInteger()
        first.start(cancelNative = { cancellations.incrementAndGet() }) {}
        second.cancel()
        assertEquals(0, cancellations.get())
        first.complete("finished")
        first.cancel()
        assertEquals(0, cancellations.get())
    }
}
