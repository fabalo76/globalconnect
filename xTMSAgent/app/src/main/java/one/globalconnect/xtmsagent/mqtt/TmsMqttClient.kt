package one.globalconnect.xtmsagent.mqtt

import android.util.Log
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import one.globalconnect.xtmsagent.TMSFunc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory

private const val TAG = "TmsMqttClient"
private const val SOCKET_CONNECT_TIMEOUT_SECONDS = 15L
private const val TLS_HANDSHAKE_TIMEOUT_SECONDS = 30L
private const val MQTT_CONNECT_TIMEOUT_SECONDS = 15L

fun termNotifyTopic(termId: String) = "tms/device/$termId/notify"
fun termStatusTopic(termId: String) = "tms/device/$termId/status"
fun termHeartbeatTopic(termId: String) = "tms/device/$termId/heartbeat"
fun termCmdTopic(termId: String) = "tms/device/$termId/cmd"
fun termTaskTopic(termId: String) = "tms/device/$termId/task"
fun termTaskRecoveryTopic(termId: String) = "tms/device/$termId/task/recovery/+"
fun termEasyTopic(termId: String) = termTaskTopic(termId)
fun termTaskAckTopic(termId: String) = "tms/device/$termId/task/ack"
fun termEasyAckTopic(termId: String) = termTaskAckTopic(termId)
fun termConfigRequestTopic(termId: String) = "tms/device/$termId/config/request"
fun termConfigResponseTopic(termId: String) = "tms/device/$termId/config/response"
fun termTransactionTopic(env: String, termId: String) =
    "\$aws/rules/tms_transaction_ingest_$env/tms/device/$termId/transaction"
fun termSettlementTopic(termId: String) = "tms/device/$termId/settlement"
fun termAdminRequestTopic(termId: String) = "tms/device/$termId/admin/request"
fun termApplicationLicenseRequestTopic(termId: String) = "tms/device/$termId/license/request"
fun termApplicationLicenseResponseTopic(termId: String) = "tms/device/$termId/license/response"
fun termCfgAckTopic(termId: String) = termConfigRequestTopic(termId)
fun termParamReqTopic(termId: String) = termConfigRequestTopic(termId)
fun termParamResTopic(termId: String) = termConfigResponseTopic(termId)
fun termVerReqTopic(termId: String) = termConfigRequestTopic(termId)
fun termVerInfoTopic(termId: String) = termConfigResponseTopic(termId)
fun termHkReqTopic(termId: String) = termConfigRequestTopic(termId)
fun termHkRespTopic(termId: String) = termConfigResponseTopic(termId)
const val BROADCAST_NOTIFY_TOPIC = "tms/broadcast/notify"

fun buildAwsIotMqttClient(
    brokerHost: String,
    termId: String,
    keyManagerFactory: KeyManagerFactory,
    onDisconnected: (MqttClientDisconnectedContext) -> Unit
): Mqtt3AsyncClient {
    return Mqtt3Client.builder()
        .identifier(termId)
        .transportConfig()
            .serverHost(brokerHost)
            .serverPort(TMSFunc.mqttCfg.mqtt_port)
            .socketConnectTimeout(SOCKET_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .mqttConnectTimeout(MQTT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .sslConfig()
                .keyManagerFactory(keyManagerFactory)
                .protocols(listOf("TLSv1.2", "TLSv1.3"))
                .handshakeTimeout(TLS_HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .applySslConfig()
            .applyTransportConfig()
        .addConnectedListener {
            Log.i(TAG, "AWS IoT MQTT connected (clientId=$termId)")
        }
        .addDisconnectedListener { event ->
            onDisconnected(event)
        }
        .buildAsync()
}

fun connectAwsIotMqttClient(
    client: Mqtt3AsyncClient
): CompletableFuture<Mqtt3ConnAck> {
    return client.connectWith()
        .cleanSession(false)
        .keepAlive(TMSFunc.mqttCfg.keepalive)
        .send()
}
