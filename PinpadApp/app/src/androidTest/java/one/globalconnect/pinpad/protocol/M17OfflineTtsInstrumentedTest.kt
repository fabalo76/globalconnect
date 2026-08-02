package one.globalconnect.pinpad.protocol

import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.globalconnect.pinpad.PinpadApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class M17OfflineTtsInstrumentedTest {
    @Test
    fun m17SynthesizesEnglishAndSpanishThroughManagedRhVoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PinpadApplication
        val handler = PinpadProtocolFactory.create(app)
        try {
            assertEquals('0', sendM17(handler, "en", "Global Connect offline English test."))
            assertEquals('0', sendM17(handler, "es-MX", "Prueba sin conexion con la voz Mateo."))
            // setVoice for the Spanish command waits for the preceding English
            // synthesis. Keep the handler alive long enough for Mateo to finish
            // before shutdown calls TextToSpeech.stop().
            SystemClock.sleep(15_000)
        } finally {
            handler.shutdown()
        }
    }

    private fun sendM17(
        handler: PinpadProtocolHandler,
        language: String,
        text: String,
    ): Char {
        val encodedText = Base64.encodeToString(
            text.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val payload = "$language\u001c$encodedText".toByteArray(StandardCharsets.US_ASCII)
        val request = codec.encode(
            PINPADFrame(PINPADFrameType.Transaction, "M17", payload),
        )

        repeat(20) {
            val status = handler.onBytesReceived(request)
                .asSequence()
                .map { codec.decode(it) }
                .filterIsInstance<PINPADFrameCodec.DecodeResult.Valid>()
                .map { it.frame }
                .firstOrNull { it.commandId == "M17" }
                ?.payload
                ?.toString(StandardCharsets.US_ASCII)
                ?.singleOrNull()
                ?: error("M17 response frame was not returned")

            // Complete the host response handshake before the next command.
            handler.onBytesReceived(byteArrayOf(PINPADControl.ACK))
            if (status != '2') return status
            SystemClock.sleep(1_000)
        }
        return '2'
    }

    private companion object {
        val codec = PINPADFrameCodec()
    }
}
