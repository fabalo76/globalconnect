package one.globalconnect.paymentapp.ecr

import android.content.Context
import kotlinx.coroutines.CancellationException
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.parallel.echo.*
import one.globalconnect.paymentapp.uicpos.pos.host.*

internal object EcrMaintenance {
    fun validate(request: EcrMessage) {
        require(request.command in setOf("00", "F2") && request.indicator == 0 && request.response == "00" && !request.more)
        require(!request.fields["80"].isNullOrBlank() && request.fields.getValue("80").length <= 64)
        require(request.fields.keys.all { it in setOf("80", "RQ", "AI") })
    }
    suspend fun execute(context: Context, request: EcrMessage): List<EcrMessage> {
        if (request.command == "00") return EcrReportData.frames(request, emptyList(), false, message = "Application reset accepted")
        val database = GlobalConnectPaymentApplication.instance.tmsDatabase
        val terminal = database.Terminal.firstOrNull()
            ?: return EcrReportData.frames(request, emptyList(), false, "ND", "No terminal configured")
        val acquirers = database.Acquirer.filter { request.fields["AI"] == null || it.AcqID == request.fields["AI"] }
        if (acquirers.isEmpty()) return EcrReportData.frames(request, emptyList(), false, "ND", "Acquirer not found")
        var failed = false
        val fields = acquirers.map { acquirer ->
            val code = try {
                val ip = requireNotNull(database.IPTab.firstOrNull { it.IPTabID == acquirer.IPTabTran })
                val result = EchoTestClient().execute(EchoTestRequest(acquirer, ip, terminal,
                    parseHostAddress(ip.PrimIpAddr), parseHostAddress(ip.SecIpAddr),
                    (ip.IPConnTime.takeIf { it > 0 } ?: 10).coerceAtMost(120).toInt() * 1000,
                    (ip.TranTimeOut.takeIf { it > 0 } ?: 60).coerceAtMost(120).toInt() * 1000,
                    1, 1, 1, ip.SSL, if (ip.SSL) AcquirerSslCache.get(ip.IPTabID.toString()) else null,
                    LengthPrefixRegistry.resolve(acquirer.HostProtocol, terminal)))
                result.isoMessage.getFieldValue(39) ?: "96"
            } catch (e: CancellationException) { throw e } catch (_: Exception) { "91" }
            if (code != "00") failed = true
            mapOf("DC" to EcrReportData.text(acquirer.AcquirerName), "00" to code, "02" to if (code == "00") "Host connected" else "Host echo failed")
        }
        return EcrReportData.frames(request, fields, false, if (failed) "91" else "00", if (failed) "Host echo failed" else "Host echo completed")
    }
}
