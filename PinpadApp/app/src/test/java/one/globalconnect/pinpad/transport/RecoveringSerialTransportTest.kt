package one.globalconnect.pinpad.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RecoveringSerialTransportTest {
    @Test fun disconnectedReaderIsClosedBeforeReplacementAndStaleCallbacksAreIgnored() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val first = FakeTransport()
        val second = FakeTransport()
        val created = AtomicInteger()
        val opened = CountDownLatch(2)
        val received = CopyOnWriteArrayList<Byte>()
        val errors = AtomicInteger()
        val recovery = RecoveringSerialTransport(worker, {
            if (created.getAndIncrement() == 0) first else {
                assertTrue(first.stopped)
                second
            }
        }, { opened.countDown() }, { _, _ -> }, { 5 })
        try {
            recovery.start(listener(errors, received))
            assertTrue(first.started.await(2, TimeUnit.SECONDS))
            worker.submit {}.get(2, TimeUnit.SECONDS)
            first.listener.onTransportError(IllegalStateException("receive failed: -4008"))
            first.listener.onTransportError(IllegalStateException("duplicate failure"))
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            first.listener.onBytesReceived(byteArrayOf(1))
            second.listener.onBytesReceived(byteArrayOf(2))
            assertEquals(listOf(2.toByte()), received.toList())
            assertEquals(1, errors.get())
            assertEquals(2, created.get())
        } finally {
            recovery.stop()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun failedStartupRetriesWithoutReplayingFailedWrites() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val first = FakeTransport(failStart = true)
        val second = FakeTransport(failSend = true)
        val third = FakeTransport()
        val created = AtomicInteger()
        val reopened = CountDownLatch(1)
        val recovery = RecoveringSerialTransport(worker, {
            listOf(first, second, third)[created.getAndIncrement()]
        }, { if (created.get() == 3) reopened.countDown() }, { _, _ -> }, { 5 })
        try {
            recovery.start(listener(AtomicInteger(), CopyOnWriteArrayList()))
            assertTrue(second.started.await(2, TimeUnit.SECONDS))
            recovery.send(byteArrayOf(9))
            assertTrue(reopened.await(2, TimeUnit.SECONDS))
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertTrue(first.stopped)
            assertTrue(second.stopped)
            assertEquals(1, second.writes.get())
            assertEquals(0, third.writes.get())
        } finally {
            recovery.stop()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun stopPreventsScheduledRetryAndLateErrorFromReopeningPort() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val first = FakeTransport()
        val retries = CountDownLatch(1)
        val created = AtomicInteger()
        val recovery = RecoveringSerialTransport(worker, {
            created.incrementAndGet()
            first
        }, {}, { _, _ -> retries.countDown() }, { 50 })
        try {
            recovery.start(listener(AtomicInteger(), CopyOnWriteArrayList()))
            assertTrue(first.started.await(2, TimeUnit.SECONDS))
            first.listener.onTransportError(IllegalStateException("disconnected"))
            assertTrue(retries.await(2, TimeUnit.SECONDS))
            recovery.stop()
            first.listener.onTransportError(IllegalStateException("late callback"))
            worker.schedule({}, 100, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS)
            assertTrue(first.stopped)
            assertEquals(1, created.get())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun recoveryBackoffIsCapped() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (1..7).map(::serialRecoveryDelayMs))
        assertEquals(30000L, serialRecoveryDelayMs(Int.MAX_VALUE))
    }

    @Test fun settingsReloadCannotOpenNewDriverUntilOldCleanupSucceeds() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val slot = SerialTransportSlot()
        val allowClose = AtomicBoolean(false)
        val oldStarted = CountDownLatch(1)
        val cleanupFailed = CountDownLatch(1)
        val newOpened = CountDownLatch(1)
        val newCreated = AtomicInteger()
        val oldDriver = object : PINPADTransport {
            override fun start(listener: PINPADTransport.Listener) { oldStarted.countDown() }
            override fun send(bytes: ByteArray) = Unit
            override fun stop() { check(allowClose.get()) { "reader still stopping" } }
        }
        val old = RecoveringSerialTransport(worker, { oldDriver }, {}, { _, _ -> }, { 5 }, slot)
        val next = RecoveringSerialTransport(worker, {
            newCreated.incrementAndGet()
            FakeTransport()
        }, { newOpened.countDown() }, { _, _ -> }, { 5 }, slot)
        val nextListener = object : PINPADTransport.Listener {
            override fun onBytesReceived(bytes: ByteArray) = Unit
            override fun onTransportError(error: Throwable) { cleanupFailed.countDown() }
        }
        try {
            old.start(listener(AtomicInteger(), CopyOnWriteArrayList()))
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
            old.stop()
            next.start(nextListener)
            assertTrue(cleanupFailed.await(2, TimeUnit.SECONDS))
            assertEquals(0, newCreated.get())
            allowClose.set(true)
            assertTrue(newOpened.await(2, TimeUnit.SECONDS))
            assertEquals(1, newCreated.get())
        } finally {
            allowClose.set(true)
            old.stop()
            next.stop()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun listener(errors: AtomicInteger, received: MutableList<Byte>) = object : PINPADTransport.Listener {
        override fun onBytesReceived(bytes: ByteArray) { received.addAll(bytes.toList()) }
        override fun onTransportError(error: Throwable) { errors.incrementAndGet() }
    }

    private class FakeTransport(val failStart: Boolean = false, val failSend: Boolean = false) : PINPADTransport {
        lateinit var listener: PINPADTransport.Listener
        val started = CountDownLatch(1)
        val writes = AtomicInteger()
        @Volatile var stopped = false
        override fun start(listener: PINPADTransport.Listener) {
            this.listener = listener
            started.countDown()
            if (failStart) throw IllegalStateException("connect failed")
        }
        override fun send(bytes: ByteArray) {
            writes.incrementAndGet()
            if (failSend) throw IllegalStateException("send failed")
        }
        override fun stop() { stopped = true }
    }
}
