package one.globalconnect.pinpad.transport

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Serial lifecycle operations share one worker, including across settings reloads. */
internal class RecoveringSerialTransport(
    private val worker: ScheduledExecutorService,
    private val factory: () -> PINPADTransport,
    private val onOpened: () -> Unit,
    private val onRetry: (Int, Long) -> Unit,
    private val retryDelay: (Int) -> Long = ::serialRecoveryDelayMs,
    private val driverSlot: SerialTransportSlot = SerialTransportSlot(),
) : PINPADTransport {
    private val running = AtomicBoolean(false)
    @Volatile private var active: Attempt? = null
    private var failures = 0
    private lateinit var listener: PINPADTransport.Listener

    val isOpen: Boolean get() = active?.let(::isCurrent) == true

    override fun start(listener: PINPADTransport.Listener) {
        check(running.compareAndSet(false, true)) { "Recovery transport already started" }
        this.listener = listener
        worker.execute(::open)
    }

    override fun send(bytes: ByteArray) {
        if (!running.get()) return
        val attempt = active ?: return
        val payload = bytes.copyOf()
        try {
            worker.execute {
                if (isCurrent(attempt)) {
                    try {
                        attempt.transport.send(payload)
                    } catch (error: Exception) {
                        fail(attempt, error)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (running.get()) throw error
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        active = null
        worker.execute {
            try {
                closeOwned()
            } catch (error: Exception) {
                listener.onTransportError(error)
            }
        }
    }

    private fun open() {
        if (!running.get()) return
        var attempt: Attempt? = null
        try {
            // Never open a replacement while the previous reader still owns the driver.
            closeOwned()
            val next = factory()
            driverSlot.transport = next
            attempt = Attempt(next)
            active = attempt
            val current = attempt
            next.start(object : PINPADTransport.Listener {
                override fun onBytesReceived(bytes: ByteArray) {
                    if (isCurrent(current)) {
                        worker.execute { if (isCurrent(current)) failures = 0 }
                        listener.onBytesReceived(bytes)
                    }
                }

                override fun onTransportError(error: Throwable) = fail(current, error)
            })
            if (isCurrent(current)) onOpened()
        } catch (error: Exception) {
            if (attempt != null) fail(attempt, error)
            else if (running.get()) {
                listener.onTransportError(error)
                scheduleRetry()
            }
        }
    }

    private fun fail(attempt: Attempt, error: Throwable) {
        if (!running.get() || active !== attempt || !attempt.failed.compareAndSet(false, true)) return
        listener.onTransportError(error)
        worker.execute {
            if (running.get() && active === attempt) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running.get()) return
        failures = (failures + 1).coerceAtMost(31)
        val delay = retryDelay(failures)
        onRetry(failures, delay)
        // Release the broken port now, then let the driver settle before opening again.
        try {
            closeOwned()
        } catch (error: Exception) {
            listener.onTransportError(error)
        }
        worker.schedule(::open, delay, TimeUnit.MILLISECONDS)
    }

    private fun closeOwned() {
        driverSlot.transport?.stop()
        driverSlot.transport = null
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        running.get() && active === attempt && !attempt.failed.get()

    private class Attempt(val transport: PINPADTransport) {
        val failed = AtomicBoolean(false)
    }
}

/** Retain failed cleanup across replacements so two readers never acquire one driver. */
internal class SerialTransportSlot {
    var transport: PINPADTransport? = null // Accessed only by the shared worker.
}

internal fun serialRecoveryDelayMs(failures: Int): Long =
    (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
