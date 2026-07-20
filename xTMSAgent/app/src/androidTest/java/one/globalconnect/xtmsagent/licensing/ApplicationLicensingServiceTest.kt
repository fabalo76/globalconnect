package one.globalconnect.xtmsagent.licensing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ApplicationLicensingServiceTest {
    @Test
    fun bindAndMalformedRequestReturnsErrorWithoutCrashingService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bound = CountDownLatch(1)
        val responseReceived = CountDownLatch(1)
        var service: Messenger? = null
        var errorCode: String? = null

        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != ApplicationLicensingService.MSG_LICENSE_RESPONSE) return
                val response = message.data.getString(ApplicationLicensingService.KEY_RESPONSE_JSON).orEmpty()
                errorCode = JSONObject(response).optString("errorCode")
                responseReceived.countDown()
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = Messenger(binder)
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        val didBind = context.bindService(
            Intent(context, ApplicationLicensingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(didBind)

        try {
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            service?.send(Message.obtain(null, ApplicationLicensingService.MSG_REQUEST_LICENSE).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString(ApplicationLicensingService.KEY_REQUEST_ID, "malformed-request")
                    putString(ApplicationLicensingService.KEY_PACKAGE_NAME, "invalid.package")
                }
            })

            assertTrue(responseReceived.await(10, TimeUnit.SECONDS))
            assertEquals("CALLER_PACKAGE_MISMATCH", errorCode)
        } finally {
            context.unbindService(connection)
        }
    }
}
