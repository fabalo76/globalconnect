package one.globalconnect.xtmsagent

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.nexgo.oaf.apiv3.APIProxy
import one.globalconnect.xtmsagent.btn_move.GridAdapter
import one.globalconnect.xtmsagent.launcher.ACTION_LAUNCHER_CONFIG_UPDATED
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import one.globalconnect.xtmsagent.install.LocalApkInstaller
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.TmsMqttService
import one.globalconnect.xtmsagent.nexgo.PhysicalKeypadInputPolicy
import one.globalconnect.xtmsagent.policy.FactoryTmsManager
import one.globalconnect.xtmsagent.policy.FactoryTmsState
import one.globalconnect.xtmsagent.policy.UsbFileTransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ConfigMenuActivity"

/**
 * Configuration submenu shown when the user taps the "Config" button in the launcher.
 * Uses the same grid-button style as the main launcher screen.
 *
 * Buttons:
 *  1. Android Configuration  — password-protected → Android system Settings
 *  2. TMS Configuration      — password-protected → TmsConfigActivity
 *  3. WiFi Configuration     — no password        → Android WiFi Settings
 *  4. Network Configuration  — no password        → Mobile network settings
 *  5. Update                 — no password        → HouseKeeping + LauncherConfig + status report
 *  6. Change Password        — (when pwd protection on) → change admin PIN
 *  7. Change Super Password  — (when pwd protection on) → change super-user seeds
 *  8. Local Install          — password-protected → choose and install an APK
 *  9. Back                   — no password        → finish()
 */
class ConfigMenuActivity : AppCompatActivity() {

    private val timeoutHandler  = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finish() }

    // Rate-limit password attempts: lock out for 30 s after 3 consecutive failures.
    private var pwdFailCount   = 0
    private var pwdLockUntilMs = 0L
    private var localInstallRunning = false

    private val selectLocalApk = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let(::installLocalApk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_menu)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.config_menu_title)
        }

        applyTmsTheme()
        setupGrid()
    }

    override fun onResume() {
        super.onResume()
        applyTmsTheme()
        if (!localInstallRunning) resetIdleTimeout()
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

    @Suppress("DEPRECATION")
    private fun applyTmsTheme() {
        val theme = MainActivity.stTheme
        window.statusBarColor     = theme.status_bar_color.toColorInt()
        window.navigationBarColor = theme.navigation_bar_color.toColorInt()
        try {
            val platform = APIProxy.getDeviceEngine(this).platform
            if (theme.enable_control_bar) platform.enableControlBar()
            else platform.disableControlBar()
            if (theme.enable_navigation_bar) platform.showNavigationBar()
            else platform.hideNavigationBar()
        } catch (e: Exception) {
            Log.w(TAG, "applyTmsTheme: device bars unavailable — ${e.message}")
        }
    }

    // ── Grid setup ────────────────────────────────────────────────────────────

    private var configItems = listOf<GridAdapter.ButtonItem>()
    private var itemHeightPx = 0
    private var pageCount = 0

    private fun setupGrid() {
        val items = mutableListOf(
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_android),
                packageName     = "cfg_android",
                backgroundColor = "#1565C0".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.settings),
                onClickAction   = Runnable {
                    requestPassword { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_tms),
                packageName     = "cfg_tms",
                backgroundColor = "#2E7D32".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_tms_server),
                onClickAction   = Runnable {
                    requestPassword(allowDebugBypass = false) {
                        startActivity(Intent(this, TmsConfigActivity::class.java))
                    }
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_wifi),
                packageName     = "cfg_wifi",
                backgroundColor = "#6A1B9A".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_wifi),
                onClickAction   = Runnable {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_network),
                packageName     = "cfg_network",
                backgroundColor = "#00695C".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_network),
                onClickAction   = Runnable {
                    startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_update),
                packageName     = "cfg_update",
                backgroundColor = "#E65100".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.update),
                onClickAction   = Runnable { triggerUpdate() }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_diagnostics),
                packageName     = "cfg_diagnostics",
                backgroundColor = "#455A64".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_tms_server),
                onClickAction   = Runnable {
                    startActivity(Intent(this, DiagnosticsActivity::class.java))
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_factory_tms),
                packageName     = "cfg_factory_tms",
                backgroundColor = "#5D4037".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_tms_server),
                onClickAction   = Runnable { showFactoryTmsDialog() }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_usb),
                packageName     = "cfg_usb",
                backgroundColor = "#37474F".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_network),
                onClickAction   = Runnable {
                    requestPassword { showUsbFileTransferDialog() }
                }
            ),
            GridAdapter.ButtonItem(
                text            = getString(R.string.config_local_install),
                packageName     = "cfg_local_install",
                backgroundColor = "#7B1FA2".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.update),
                onClickAction   = Runnable {
                    requestPassword { openLocalApkPicker() }
                }
            )
        )

        if (MainActivity.stTheme.system_pwd_protection) {
            items.add(GridAdapter.ButtonItem(
                text            = getString(R.string.chg_pwd),
                packageName     = "cfg_chg_pwd",
                backgroundColor = "#4527A0".toColorInt(),
                iconDrawable    = ContextCompat.getDrawable(this, R.drawable.changepwd),
                onClickAction   = Runnable { showChangePwdDialog() }
            ))
        }

        items.add(GridAdapter.ButtonItem(
            text            = getString(R.string.config_back),
            packageName     = "cfg_back",
            backgroundColor = "#37474F".toColorInt(),
            iconDrawable    = ContextCompat.getDrawable(this, R.drawable.ic_back),
            onClickAction   = Runnable { finish() }
        ))

        configItems = items

        val vp = findViewById<ViewPager2>(R.id.viewPagerConfig)
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateDots(position, pageCount) }
        })
        vp.doOnLayout {
            val rows = resources.getInteger(R.integer.grid_rows)
            itemHeightPx = it.height / rows
            buildPages(vp, rows * 2)
        }
    }

    private fun buildPages(vp: ViewPager2, buttonsPerPage: Int) {
        val pages = mutableListOf<List<GridAdapter.ButtonItem>>()
        var i = 0
        while (i < configItems.size) {
            pages.add(configItems.subList(i, minOf(i + buttonsPerPage, configItems.size)))
            i += buttonsPerPage
        }
        if (pages.isEmpty()) pages.add(emptyList())

        pageCount = pages.size
        // Use appList.size as startIndex so GridAdapter.onClearView is always a no-op
        val startIndices = List(pages.size) { MainActivity.appList.size }
        vp.adapter = LauncherPagerAdapter(pages, itemHeightPx, startIndices)
        updateDots(0, pageCount)
    }

    private fun updateDots(current: Int, count: Int) {
        val layout = findViewById<LinearLayout>(R.id.configDotsIndicator) ?: return
        layout.removeAllViews()
        if (count <= 1) { layout.visibility = View.GONE; return }
        layout.visibility = View.VISIBLE
        val dotSizePx  = (resources.getDimension(R.dimen.dot_size)).toInt()
        val dotMarginPx = (resources.getDimension(R.dimen.dot_margin)).toInt()
        for (idx in 0 until count) {
            val dot = ImageView(this).apply {
                val lp = LinearLayout.LayoutParams(dotSizePx, dotSizePx).also {
                    it.setMargins(dotMarginPx, 0, dotMarginPx, 0)
                }
                layoutParams = lp
                setImageResource(
                    if (idx == current) android.R.drawable.presence_online
                    else android.R.drawable.presence_invisible
                )
            }
            layout.addView(dot)
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val b  = md.digest(s.toByteArray())
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun getSuperCode(seed: String): String {
        val date   = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Calendar.getInstance().time)
        val digest = sha256("$date$seed").substring(0, 8)
        val n      = digest.toLong(16) % 100_000_000L
        return "%08d".format(n)
    }

    // ── Password gate ─────────────────────────────────────────────────────────

    private fun requestPassword(
        allowDebugBypass: Boolean = true,
        onSuccess: () -> Unit,
    ) {
        if (BuildConfig.DEBUG && allowDebugBypass) { onSuccess(); return }

        val now = SystemClock.elapsedRealtime()
        if (now < pwdLockUntilMs) {
            val remaining = (pwdLockUntilMs - now) / 1000
            Toast.makeText(this,
                "${getString(R.string.pls_wait)} $remaining ${getString(R.string.seconds)}",
                Toast.LENGTH_SHORT).show()
            return
        }

        val view  = layoutInflater.inflate(R.layout.pwd_input, null)
        view.findViewById<TextView>(R.id.title).text = getString(R.string.input_pwd)
        val edit1 = view.findViewById<EditText>(R.id.password1)
        val edit2 = view.findViewById<EditText>(R.id.password2)
        PhysicalKeypadInputPolicy.configure(edit1, edit2)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (verifyPassword(edit1.text.toString(), edit2.text.toString())) {
                    pwdFailCount = 0
                    onSuccess()
                } else {
                    pwdFailCount++
                    if (pwdFailCount >= 3) {
                        pwdLockUntilMs = SystemClock.elapsedRealtime() + 30_000L
                        pwdFailCount   = 0
                    }
                    Toast.makeText(this, getString(R.string.pwd_err), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun verifyPassword(p1: String, p2: String): Boolean {
        val internalPath = MainActivity.vg_sIntrenalPath
        if (internalPath.isBlank()) return false

        return try {
            val super0 = getSuperCode(SuperPwdStore.loadSeed(this, 0))
            val super1 = getSuperCode(SuperPwdStore.loadSeed(this, 1))
            if (p1 == super0 && p2 == super1) return true

            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val doc     = factory.newDocumentBuilder()
                .parse(java.io.File(internalPath, "Password.xml"))
            doc.documentElement.normalize()

            val pwdNode  = doc.documentElement.getElementsByTagName("pwd0")
            if (pwdNode.length == 0) return false
            val attrs    = pwdNode.item(0).attributes
            val storedP1 = attrs.getNamedItem("p0")?.nodeValue ?: return false
            val storedP2 = attrs.getNamedItem("p1")?.nodeValue ?: return false

            sha256(p1) == storedP1 && sha256(p2) == storedP2
        } catch (e: Exception) {
            Log.e(TAG, "Password check failed: ${e.message}", e)
            false
        }
    }

    // ── Change admin password ─────────────────────────────────────────────────

    private fun showChangePwdDialog() {
        // Step 1 — verify current password
        val verifyView = layoutInflater.inflate(R.layout.pwd_input, null)
        verifyView.findViewById<TextView>(R.id.title).text = getString(R.string.input_pwd)
        val cur1 = verifyView.findViewById<EditText>(R.id.password1)
        val cur2 = verifyView.findViewById<EditText>(R.id.password2)
        PhysicalKeypadInputPolicy.configure(cur1, cur2)

        val verifyDlg = AlertDialog.Builder(this)
            .setView(verifyView)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (verifyPassword(cur1.text.toString(), cur2.text.toString())) {
                    showChangePwdNewDialog()
                } else {
                    showMsg(getString(R.string.pwd_err))
                }
            }
            .setNeutralButton(R.string.cancel, null)
            .create()
        verifyDlg.setCancelable(false)
        verifyDlg.setCanceledOnTouchOutside(false)
        verifyDlg.setOnShowListener {
            verifyDlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            verifyDlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        verifyDlg.show()
    }

    private fun showChangePwdNewDialog() {
        val view    = layoutInflater.inflate(R.layout.pwd_change, null)
        view.findViewById<TextView>(R.id.title).text = getString(R.string.chg_pwd)
        val new1    = view.findViewById<EditText>(R.id.newpassword1)
        val new2    = view.findViewById<EditText>(R.id.newpassword2)
        val renew1  = view.findViewById<EditText>(R.id.renewpassword1)
        val renew2  = view.findViewById<EditText>(R.id.renewpassword2)
        PhysicalKeypadInputPolicy.configure(new1, new2, renew1, renew2)

        val dlg = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val s1 = new1.text.toString()
                val s2 = new2.text.toString()
                when {
                    s1.length < 7 || s2.length < 7 ->
                        showMsg(getString(R.string.chg_pwd_err_no_7))
                    s1 == s2 ->
                        showMsg(getString(R.string.chg_pwd_err_same))
                    s1 == renew1.text.toString() && s2 == renew2.text.toString() -> {
                        MainActivity.instance?.SavePwd(0, s1, s2)
                        MainActivity.writeLog("Change Admin Password OK (from ConfigMenu)")
                        showMsg(getString(R.string.chg_pwd_ok))
                    }
                    else -> showMsg(getString(R.string.chg_pwd_err_no_match))
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.cancel, null)
            .create()
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        dlg.show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showMsg(msg: String) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
            .create()
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnShowListener {
            dlg.findViewById<TextView>(android.R.id.message)
                ?.setTextSize(TypedValue.COMPLEX_UNIT_PT, 10f)
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        dlg.show()
    }

    private fun openLocalApkPicker() {
        selectLocalApk.launch(
            arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
            ),
        )
    }

    private fun installLocalApk(uri: Uri) {
        localInstallRunning = true
        timeoutHandler.removeCallbacks(timeoutRunnable)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.local_install_title)
            .setMessage(R.string.local_install_progress)
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(loggingCoroutineExceptionHandler(TAG)) {
            val result = withContext(Dispatchers.IO) {
                LocalApkInstaller.install(this@ConfigMenuActivity, uri)
            }
            progressDialog.dismiss()
            localInstallRunning = false
            resetIdleTimeout()

            val version = result.versionName.ifBlank {
                result.versionCode.takeIf { it >= 0L }?.toString().orEmpty()
            }
            if (result.success) {
                val message = getString(
                    R.string.local_install_success,
                    result.packageName,
                    version,
                )
                Log.i(TAG, "$message: ${result.message}")
                MainActivity.writeLog("Local install succeeded: ${result.packageName} versionCode=${result.versionCode}")
                TmsMqttManager.queueFullStatusReport(
                    this@ConfigMenuActivity,
                    "Local application installed: ${result.packageName}",
                )
                showMsg(message)
            } else {
                val message = getString(R.string.local_install_failure, result.message)
                Log.e(TAG, "Local install failed package=${result.packageName}: ${result.message}")
                MainActivity.writeLog("Local install failed: ${result.packageName} ${result.message}")
                showMsg(message)
            }
        }
    }

    private fun showUsbFileTransferDialog() {
        if (!TmsDeviceAdminReceiver.isDeviceOwner(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.usb_config_title)
                .setMessage(R.string.usb_config_owner_required)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val enabled = UsbFileTransferManager.isEnabled(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.usb_config_title)
            .setMessage(
                if (enabled) R.string.usb_config_status_enabled
                else R.string.usb_config_status_disabled
            )
            .setPositiveButton(
                if (enabled) R.string.usb_config_disable else R.string.usb_config_enable
            ) { _, _ ->
                if (enabled) {
                    applyUsbFileTransferEnabled(false)
                } else {
                    confirmUsbFileTransferEnable()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmUsbFileTransferEnable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.usb_config_enable)
            .setMessage(R.string.usb_config_enable_warning)
            .setPositiveButton(R.string.usb_config_enable) { _, _ ->
                applyUsbFileTransferEnabled(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyUsbFileTransferEnabled(enabled: Boolean) {
        val result = UsbFileTransferManager.setEnabled(this, enabled)
        val message = if (result.success) {
            MainActivity.writeLog(
                "USB file transfer ${if (enabled) "enabled" else "disabled"} from Config"
            )
            if (enabled) {
                getString(R.string.usb_config_updated_enabled)
            } else {
                getString(R.string.usb_config_updated_disabled)
            }
        } else {
            getString(R.string.usb_config_update_failed, result.code)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.usb_config_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showFactoryTmsDialog() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                FactoryTmsManager.status(this@ConfigMenuActivity)
            }
            val message = when (status.state) {
                FactoryTmsState.ENABLED -> getString(R.string.factory_tms_status_enabled)
                FactoryTmsState.DISABLED -> getString(R.string.factory_tms_status_disabled)
                FactoryTmsState.NOT_INSTALLED -> getString(R.string.factory_tms_status_missing)
                FactoryTmsState.DEVICE_OWNER_REQUIRED ->
                    getString(R.string.factory_tms_status_owner_required)
                FactoryTmsState.ERROR ->
                    getString(R.string.factory_tms_status_error, status.code)
            }

            val builder = AlertDialog.Builder(this@ConfigMenuActivity)
                .setTitle(R.string.factory_tms_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)

            when (status.state) {
                FactoryTmsState.ENABLED -> builder.setPositiveButton(
                    R.string.factory_tms_disable,
                ) { _, _ ->
                    requestPassword { confirmFactoryTmsDisable() }
                }
                FactoryTmsState.DISABLED -> builder.setPositiveButton(
                    R.string.factory_tms_enable,
                ) { _, _ ->
                    requestPassword { applyFactoryTmsEnabled(true) }
                }
                else -> Unit
            }
            builder.show()
        }
    }

    private fun confirmFactoryTmsDisable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.factory_tms_disable)
            .setMessage(R.string.factory_tms_disable_warning)
            .setPositiveButton(R.string.factory_tms_disable_confirm) { _, _ ->
                applyFactoryTmsEnabled(false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyFactoryTmsEnabled(enabled: Boolean) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FactoryTmsManager.setEnabled(this@ConfigMenuActivity, enabled)
            }
            val message = if (result.success) {
                MainActivity.writeLog(
                    "Factory Nexgo TMS ${if (enabled) "enabled" else "disabled"} " +
                        "changed=${result.changed}",
                )
                if (enabled) {
                    getString(R.string.factory_tms_updated_enabled)
                } else {
                    getString(R.string.factory_tms_updated_disabled)
                }
            } else {
                MainActivity.writeLog(
                    "Factory Nexgo TMS policy failed state=${result.status.state} " +
                        "code=${result.code}",
                )
                getString(R.string.factory_tms_update_failed, result.code)
            }
            if (result.success && !enabled) {
                showFactoryTmsRestartDialog(message)
            } else {
                showMsg(message)
            }
        }
    }

    private fun showFactoryTmsRestartDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.factory_tms_title)
            .setMessage(message)
            .setPositiveButton(R.string.factory_tms_restart_now) { _, _ ->
                try {
                    val devicePolicyManager =
                        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    devicePolicyManager.reboot(TmsDeviceAdminReceiver.componentName(this))
                } catch (exception: Exception) {
                    MainActivity.writeLog(
                        "Factory Nexgo TMS restart failed: ${exception.javaClass.simpleName}",
                    )
                    showMsg(
                        getString(
                            R.string.factory_tms_restart_failed,
                            exception.javaClass.simpleName,
                        ),
                    )
                }
            }
            .setNegativeButton(R.string.factory_tms_restart_later, null)
            .show()
    }

    // ── Update action ─────────────────────────────────────────────────────────

    private fun triggerUpdate() {
        Log.i(TAG, "Manual update triggered from ConfigMenu")
        MainActivity.writeLog("Manual update triggered from ConfigMenu")

        // Build progress dialog
        val dp8  = (8  * resources.displayMetrics.density).toInt()
        val dp16 = (16 * resources.displayMetrics.density).toInt()

        val statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp16, dp8, dp16, dp8)
            text = getString(R.string.update_checking)
        }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            setPadding(dp16, dp8, dp16, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spinner)
            addView(statusText)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val progressDlg = AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setView(scroll)
            .setCancelable(false)
            .create()
        progressDlg.show()

        fun appendLine(line: String) = runOnUiThread {
            statusText.text = "${statusText.text}\n$line"
        }

        lifecycleScope.launch(loggingCoroutineExceptionHandler(TAG)) {
            val updates = mutableListOf<String>()

            statusText.text = getString(R.string.update_connecting_iot)
            TmsMqttService.start(this@ConfigMenuActivity)
            val connection = withContext(Dispatchers.IO) {
                TmsMqttManager.ensureConnected()
            }
            if (!connection.connected) {
                Log.w(TAG, "Manual update aborted: IoT connection unavailable: ${connection.text}")
                MainActivity.writeLog("Manual update aborted: IoT connection unavailable")
                progressDlg.dismiss()
                showMsg(getString(R.string.update_iot_connection_failed, connection.text))
                return@launch
            }
            appendLine(getString(R.string.update_iot_connected))

            // ── Launcher config ───────────────────────────────────────────────
            // Must run first — a config change may assign a different prog-app file set,
            // so verreq must see the updated assignment.
            var launcherUpdated = false
            val launcherReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) { launcherUpdated = true }
            }
            appendLine(getString(R.string.update_checking_launcher))
            ContextCompat.registerReceiver(this@ConfigMenuActivity, launcherReceiver, IntentFilter(ACTION_LAUNCHER_CONFIG_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)

            withContext(Dispatchers.IO) {
                try {
                    LauncherConfigManager.downloadAndApply(
                        this@ConfigMenuActivity, urgent = true, sendAck = true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "LauncherConfig download failed: ${e.message}", e)
                }
                Thread.sleep(600) // let broadcast arrive on main thread
            }

            try { unregisterReceiver(launcherReceiver) } catch (_: Exception) {}
            if (launcherUpdated) updates.add(getString(R.string.update_launcher_config_updated))
            appendLine(if (launcherUpdated) getString(R.string.update_launcher_updated) else getString(R.string.update_launcher_uptodate))

            // ── HouseKeeping ──────────────────────────────────────────────────
            // Sends hkreq immediately (urgent=true, no jitter delay).
            // Server responds on hkresp topic; TmsHouseKeepingManager processes the
            // response asynchronously — any new tasks are scheduled by TmsHkScheduler.
            appendLine(getString(R.string.update_triggering_hk))
            TmsMqttManager.triggerHouseKeeping(urgent = true)
            appendLine(getString(R.string.update_hk_sent))

            // ── MQTT version request ──────────────────────────────────────────
            // Fire after launcher config so verreq reflects the current app assignment.
            // Server responds on verinfo topic; handleVerInfoMessage triggers download
            // and install in the background if a version mismatch is detected.
            appendLine(getString(R.string.update_requesting_version))
            TmsMqttManager.publishVersionRequest()
            appendLine(getString(R.string.update_version_requested))

            // ── Step 4: Status report ──────────────────────────────────────────
            withContext(Dispatchers.IO) { TmsMqttManager.publishFullStatusReport() }

            // ── Show result ────────────────────────────────────────────────────
            val summary = if (updates.isNotEmpty())
                getString(R.string.update_summary_applied) + updates.joinToString("\n• ")
            else
                getString(R.string.update_summary_uptodate)
            progressDlg.dismiss()
            showMsg(summary)

            MainActivity.writeLog("Manual update done. Updates: ${updates.size}")
        }
    }
}
