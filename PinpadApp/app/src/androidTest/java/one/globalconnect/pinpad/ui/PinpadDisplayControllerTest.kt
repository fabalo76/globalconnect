package one.globalconnect.pinpad.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinpadDisplayControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun restoreIdleScreen() {
        instrumentation.runOnMainSync(PinpadDisplayController::showIdle)
    }

    @Test
    fun keyLoadSuccessMessageReturnsToKeyInjectionMode() {
        instrumentation.runOnMainSync {
            PinpadDisplayController.showKeyInjectionMode()
            PinpadDisplayController.showMessageThenKeyInjectionMode("Key injected")
        }
        assertEquals(
            PinpadDisplayState.Message("Key injected"),
            PinpadDisplayController.state,
        )

        SystemClock.sleep(3_250L)

        assertEquals(PinpadDisplayState.KeyInjectionMode, PinpadDisplayController.state)
    }
}
