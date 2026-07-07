package com.uic.uicpaymentapp.uicpos.pos.host

import android.util.Log
import com.uic.tms.payment_app.TMS_Acquirer
import com.uic.tms.payment_app.TMSDATA
import com.uic.tms.payment_app.TMS_Terminal
import com.uic.uicpaymentapp.UICApplication

object HostSettingsResolver {
    private const val TAG = "HostSettingsResolver"
    private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10
    private const val DEFAULT_READ_TIMEOUT_SEC = 60

    fun resolve(acquirerId: String?): HostSettings? {
        val database = UICApplication.instance.container.tmsDatabase
        val acquirer = selectAcquirer(database, acquirerId) ?: return null.also {
            Log.w(TAG, "No acquirer configuration available for id=$acquirerId")
        }
        val ipProfile = database.IPTab.firstOrNull { it.IPTabID == acquirer.IPTabTran }
            ?: return null.also {
                Log.w(TAG, "Missing IP profile ${acquirer.IPTabTran} for acquirer=${acquirer.AcqID}")
            }
        val terminal = selectTerminal(database, acquirer)
        val length = LengthPrefixRegistry.resolve(acquirer.HostProtocol, terminal)
        val connectTimeout = ipProfile.IPConnTime.safeInt(DEFAULT_CONNECT_TIMEOUT_SEC)
        val readTimeout = ipProfile.TranTimeOut.safeInt(DEFAULT_READ_TIMEOUT_SEC)
        val attempts = ipProfile.AttemptIPT.safeInt(1)
        val primaryRetries = ipProfile.IPConnRetriesP.safeInt(1)
        val secondaryRetries = ipProfile.IPConnRetriesS.safeInt(1)

        return HostSettings(
            isTls = ipProfile.SSL,
            primary = parseHostAddress(ipProfile.PrimIpAddr),
            secondary = parseHostAddress(ipProfile.SecIpAddr),
            connectTimeoutSeconds = connectTimeout,
            readTimeoutSeconds = readTimeout,
            attempts = attempts,
            primaryRetries = primaryRetries,
            secondaryRetries = secondaryRetries,
            length = length,
        )
    }

    private fun selectAcquirer(database: TMSDATA, acquirerId: String?): TMS_Acquirer? {
        if (!acquirerId.isNullOrBlank()) {
            database.Acquirer.firstOrNull { it.AcqID == acquirerId }?.let { return it }
        }
        return database.Acquirer.firstOrNull()
    }

    private fun selectTerminal(database: TMSDATA, acquirer: TMS_Acquirer): TMS_Terminal? {
        return database.Terminal.firstOrNull { it.TermID == acquirer.AcqTermID }
            ?: database.Terminal.firstOrNull()
    }

    private fun Long.safeInt(default: Int): Int {
        if (this <= 0) return default
        val capped = coerceAtMost(Int.MAX_VALUE.toLong())
        return capped.toInt()
    }
}
