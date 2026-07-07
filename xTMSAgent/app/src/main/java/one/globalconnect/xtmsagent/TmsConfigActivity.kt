package one.globalconnect.xtmsagent

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.GsonBuilder
import java.io.File

private const val TAG = "TmsConfigActivity"

/**
 * Displays the current TMS / MQTT connection settings for this terminal.
 *
 * Read-only fields (provisioned by the server, not editable here):
 *   - Server address, TCP port, Web port, MQTT port
 *
 * Editable fields (operator can tune without re-provisioning):
 *   - Connection timeout (seconds)
 *   - Response timeout (seconds)
 *   - MQTT keepalive (seconds)
 *   - MQTT status interval (minutes)
 *
 * Changes are written back to the JSON config file so that [TMSFunc.ChkParamChange]
 * picks them up on the next 1-second timer tick.
 */
class TmsConfigActivity : AppCompatActivity() {

    private val timeoutHandler  = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finish() }

    // ── View references ───────────────────────────────────────────────────────
    private lateinit var etServerAddr    : EditText
    private lateinit var etTcpPort       : EditText
    private lateinit var etWebPort       : EditText
    private lateinit var etMqttPort      : EditText
    private lateinit var etConnTimeout   : EditText
    private lateinit var etRespTimeout   : EditText
    private lateinit var etMqttKeepalive : EditText
    private lateinit var etStatusInterval: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tms_config)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.config_tms)
        }

        etServerAddr     = findViewById(R.id.etServerAddr)
        etTcpPort        = findViewById(R.id.etTcpPort)
        etWebPort        = findViewById(R.id.etWebPort)
        etMqttPort       = findViewById(R.id.etMqttPort)
        etConnTimeout    = findViewById(R.id.etConnTimeout)
        etRespTimeout    = findViewById(R.id.etRespTimeout)
        etMqttKeepalive  = findViewById(R.id.etMqttKeepalive)
        etStatusInterval = findViewById(R.id.etStatusInterval)

        populateFields()

        findViewById<Button>(R.id.btnTmsConfigSave).setOnClickListener { saveConfig() }
    }

    override fun onResume() {
        super.onResume()
        resetIdleTimeout()
    }

    override fun onPause() {
        super.onPause()
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimeout()
    }

    private fun resetIdleTimeout() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, 120_000L)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private fun populateFields() {
        val tms  = TMSFunc.tmsCfg
        val mqtt = TMSFunc.mqttCfg

        etServerAddr    .setText(tms.server_addr)
        etTcpPort       .setText(tms.tcp_port.toString())
        etWebPort       .setText(tms.web_port.toString())
        etMqttPort      .setText(mqtt.mqtt_port.toString())
        etConnTimeout   .setText(tms.conn_timeout.toString())
        etRespTimeout   .setText(tms.resp_timeout.toString())
        etMqttKeepalive .setText(mqtt.keepalive.toString())
        etStatusInterval.setText(mqtt.status_interval.toString())
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveConfig() {
        val connTimeout    = etConnTimeout   .text.toString().toIntOrNull()
        val respTimeout    = etRespTimeout   .text.toString().toIntOrNull()
        val mqttKeepalive  = etMqttKeepalive .text.toString().toIntOrNull()
        val statusInterval = etStatusInterval.text.toString().toIntOrNull()

        if (connTimeout    == null || connTimeout    <= 0 ||
            respTimeout    == null || respTimeout    <= 0 ||
            mqttKeepalive  == null || mqttKeepalive  <= 0 ||
            statusInterval == null || statusInterval <= 0) {
            Toast.makeText(this, getString(R.string.tms_invalid_value), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cfgFile = resolveConfigFile() ?: run {
                Toast.makeText(this, getString(R.string.tms_config_file_not_found), Toast.LENGTH_LONG).show()
                return
            }

            // Read the existing JSON, update only the editable fields, write back.
            val gson   = GsonBuilder().setPrettyPrinting().create()
            val raw    = cfgFile.readText()
            val parsed = gson.fromJson(raw, TMSFunc.cfg::class.java)

            parsed.tms.conn_timeout    = connTimeout
            parsed.tms.resp_timeout    = respTimeout
            parsed.mqtt.keepalive      = mqttKeepalive
            parsed.mqtt.status_interval = statusInterval

            cfgFile.writeText(gson.toJson(parsed))

            // Touch the file so ChkParamChange() detects the change on its next tick.
            cfgFile.setLastModified(System.currentTimeMillis())

            Log.i(TAG, "TMS config saved: conn=${connTimeout}s resp=${respTimeout}s " +
                "keepalive=${mqttKeepalive}s interval=${statusInterval}min")
            MainActivity.writeLog("TMS config updated via ConfigMenu")

            Toast.makeText(this, getString(R.string.tms_save_ok), Toast.LENGTH_SHORT).show()
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save TMS config: ${e.message}", e)
            Toast.makeText(this, getString(R.string.tms_save_error), Toast.LENGTH_LONG).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the config file that [TMSFunc.ChkParamChange] reads, in the same priority
     * order it uses: external xtms_param override first, then internal Launcher_Config.JSON.
     */
    private fun resolveConfigFile(): File? {
        val external = File(MainActivity.vg_sXtmsParam)
        if (external.exists()) return external

        val internal = File(MainActivity.vg_sIntrenalPath, "cfg/Launcher_Config.JSON")
        if (internal.exists()) return internal

        return null
    }
}
