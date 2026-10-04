package one.globalconnect.xtmsagent

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.nexgo.PhysicalKeypadInputPolicy

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
    private var selectedProfile = TmsServerProfile.current(TMSFunc.tmsCfg)

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

        PhysicalKeypadInputPolicy.configure(
            etServerAddr,
            etTcpPort,
            etWebPort,
            etMqttPort,
            etConnTimeout,
            etRespTimeout,
            etMqttKeepalive,
            etStatusInterval,
        )

        populateFields()

        findViewById<Button>(R.id.btnTmsServerSelect).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.tms_choose_server)
                .setSingleChoiceItems(arrayOf(getString(R.string.tms_server_aws), getString(R.string.tms_server_demo)),
                    selectedProfile.ordinal) { dialog, index ->
                    selectedProfile = TmsServerProfile.entries[index]
                    val target = if (selectedProfile == TmsServerProfile.current(TMSFunc.tmsCfg))
                        TMSFunc.tmsCfg else runCatching { TmsServerProfileStore(this).load(selectedProfile) }
                            .getOrElse { selectedProfile.defaults() }
                    etServerAddr.setText(target.apiHost)
                    etWebPort.setText(target.web_port.toString())
                    etTcpPort.setText(target.tcp_port.toString())
                    etMqttPort.setText(runCatching { TmsServerProfileStore(this).mqttPort(selectedProfile) }
                        .getOrElse { if (selectedProfile == TmsServerProfile.DEMO) 443 else 8883 }.toString())
                    dialog.dismiss()
                }.setNegativeButton(R.string.cancel, null).show()
        }

        findViewById<Button>(R.id.btnTmsConfigSave).setOnClickListener { requestSave() }
        findViewById<Button>(R.id.btnResetTmsRegistration).setOnClickListener {
            confirmRegistrationReset()
        }
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

    private fun requestSave(switchConfirmed: Boolean = false) {
        lifecycleScope.launch {
          try {
            val pending = withContext(Dispatchers.IO) {
                androidx.work.WorkManager.getInstance(this@TmsConfigActivity).getWorkInfos(
                    androidx.work.WorkQuery.fromStates(listOf(androidx.work.WorkInfo.State.RUNNING,
                        androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED))).get()
            }
            if (selectedProfile != TmsServerProfile.current(TMSFunc.tmsCfg) &&
                pending.any { it.state == androidx.work.WorkInfo.State.RUNNING || "TMS_SERVER_WORK" in it.tags ||
                    it.tags.any { tag -> tag.endsWith("AwsTaskApplyWorker") || tag.endsWith("DeviceProfileWorker") } }) {
                Toast.makeText(this@TmsConfigActivity, R.string.tms_server_busy, Toast.LENGTH_LONG).show()
            } else saveConfig(switchConfirmed)
          } catch (error: Exception) {
            Log.e(TAG, "Unable to validate pending server work", error)
            Toast.makeText(this@TmsConfigActivity, R.string.tms_save_error, Toast.LENGTH_LONG).show()
          }
        }
    }

    private fun saveConfig(switchConfirmed: Boolean = false) {
        val switching = selectedProfile != TmsServerProfile.current(TMSFunc.tmsCfg)
        if (switching && !switchConfirmed) {
            AlertDialog.Builder(this)
                .setTitle(R.string.tms_choose_server)
                .setMessage(R.string.tms_switch_server_warning)
                .setPositiveButton(R.string.tms_save) { _, _ -> requestSave(true) }
                .setNegativeButton(R.string.cancel, null).show()
            return
        }
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

            if (switching) {
                val profiles = TmsServerProfileStore(this)
                val target = profiles.load(selectedProfile)
                if (target.download_credential_id.isBlank() || target.download_secret.isBlank()) {
                    Toast.makeText(this, R.string.tms_server_not_provisioned, Toast.LENGTH_LONG).show()
                    return
                }
                // Do not interrupt a download or installation when changing the server.
                if (one.globalconnect.xtmsagent.mqtt.housekeeping.TmsTaskStore.loadTasks(this)
                        .any { it.status < one.globalconnect.xtmsagent.mqtt.housekeeping.ST_ACTIVATED } ||
                    one.globalconnect.xtmsagent.easy.EasyTaskStore.loadAll().any { it.status in 1..3 } ||
                    one.globalconnect.xtmsagent.mqtt.versions.AppInstallPendingStore.hasAny(this) ||
                    one.globalconnect.xtmsagent.params.ParamManager.isDownloadInProgress()) {
                    Toast.makeText(this, R.string.tms_server_busy, Toast.LENGTH_LONG).show()
                    return
                }
                profiles.save(TMSFunc.tmsCfg)
                parsed.tms = target.copy(sn = TMSFunc.tmsCfg.sn)
                parsed.mqtt.mqtt_port = profiles.mqttPort(selectedProfile)
            }

            parsed.tms.conn_timeout    = connTimeout
            parsed.tms.resp_timeout    = respTimeout
            parsed.mqtt.keepalive      = mqttKeepalive
            parsed.mqtt.status_interval = statusInterval

            writeConfig(cfgFile, gson.toJson(parsed))
            if (switching) {
                try {
                    TmsServerProfileStore(this).select(parsed.tms)
                } catch (error: Exception) {
                    writeConfig(cfgFile, raw)
                    throw error
                }
            }

            // Touch the file so ChkParamChange() detects the change on its next tick.
            cfgFile.setLastModified(System.currentTimeMillis())

            Log.i(TAG, "TMS config saved: conn=${connTimeout}s resp=${respTimeout}s " +
                "keepalive=${mqttKeepalive}s interval=${statusInterval}min")
            MainActivity.writeLog("TMS config updated via ConfigMenu")

            Toast.makeText(this, getString(R.string.tms_save_ok), Toast.LENGTH_SHORT).show()
            if (switching) {
                // A fresh process prevents in-flight callbacks from the old server being
                // delivered through the new server's connection or credentials.
                one.globalconnect.xtmsagent.mqtt.TmsMqttService.stop(this)
                val restart = android.app.PendingIntent.getActivity(this, 4401,
                    android.content.Intent(this, one.globalconnect.xtmsagent.recovery.StartupActivity::class.java),
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
                    .set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 1500, restart)
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save TMS config: ${e.message}", e)
            Toast.makeText(this, getString(R.string.tms_save_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmRegistrationReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.tms_reset_registration_title)
            .setMessage(R.string.tms_reset_registration_warning)
            .setPositiveButton(R.string.tms_reset_registration_confirm) { _, _ ->
                resetRegistration()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetRegistration() {
        val button = findViewById<Button>(R.id.btnResetTmsRegistration)
        button.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                TmsMqttManager.resetRegistration()
            }
            button.isEnabled = true
            if (result.isSuccess) {
                MainActivity.writeLog("TMS registration reset from TMS Config")
                Toast.makeText(
                    this@TmsConfigActivity,
                    getString(R.string.tms_reset_registration_started),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Log.e(TAG, "TMS registration reset failed", result.exceptionOrNull())
                Toast.makeText(
                    this@TmsConfigActivity,
                    getString(R.string.tms_reset_registration_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun writeConfig(file: File, json: String) {
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

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
