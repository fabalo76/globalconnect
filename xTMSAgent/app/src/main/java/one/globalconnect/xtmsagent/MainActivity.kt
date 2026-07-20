package one.globalconnect.xtmsagent

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.viewpager2.widget.ViewPager2
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import com.nexgo.oaf.apiv3.APIProxy
import com.nexgo.oaf.apiv3.SystemServiceHelper
import com.nexgo.oaf.apiv3.platform.OnPlatformInitListener
import one.globalconnect.xtmsagent.btn_move.GridAdapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.concurrent.schedule
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import one.globalconnect.xtmsagent.mqtt.TmsTaskStatus
import one.globalconnect.xtmsagent.mqtt.TmsStatusSeverity
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import android.content.BroadcastReceiver
import android.content.IntentFilter
import one.globalconnect.xtmsagent.launcher.ACTION_LAUNCHER_CONFIG_UPDATED
import one.globalconnect.xtmsagent.launcher.LauncherConfigManager
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.TmsMqttService
import one.globalconnect.xtmsagent.mqtt.shouldShowTmsConnectionStatus
import one.globalconnect.xtmsagent.mqtt.ACTION_HOUSEKEEPING_COMPLETE
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_BLOCK_TERMINAL
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_SHOW_OPERATOR_MESSAGE
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_TERMINAL_NOT_REGISTERED
import one.globalconnect.xtmsagent.mqtt.notifications.ACTION_UNBLOCK_TERMINAL
import one.globalconnect.xtmsagent.mqtt.notifications.EXTRA_MESSAGE_TEXT
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore

class MainActivity : AppCompatActivity() {
    companion object {

        const val APP_HEAD: String = "DL_APP_"
        const val CFG_HEAD: String = "DL_CFG_"

        val BLOCKED_PACKAGES = setOf(
            "com.android.settings",
            "com.xgd.update",
            "com.nexgo.xtms",
        )

        var vg_sIntrenalPath = ""
        var vg_sExtrenalPath = ""
        var vg_sXtmsParam = ""

        var vg_sLogPath = ""

        var vg_sShowMsg = ""

        const val PERMISSION_REQUEST_CODE = 102
        var vg_nTryConut = 0

        var vg_sSN = ""
        var stTheme:TMSFunc.theme = TMSFunc.theme()
        var instance: MainActivity? = null

        fun Logd(exception:Exception){
            if ("release" == BuildConfig.BUILD_TYPE)
                return

            var msg = ""
            //msg += exception.stackTrace[0].className
            //msg += "_"
            msg += exception.stackTrace[0].methodName
            msg += "_"
            msg += exception.stackTrace[0].lineNumber
            if(exception.message != null){
                msg += "_"
                msg += exception.message
            }

            Log.d("dbgmsg",msg)
        }

        fun writeLog(sLog:String) {
            if(vg_sLogPath.isEmpty()) {
                Logd(Exception("vg_sLogPath is empty"))
                return
            }
            val sDateTime = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Calendar.getInstance().time)
            File(vg_sLogPath).appendText("[$sDateTime] $sLog\n")
        }

        data class AppInfo(var app_name: String, val app_package_name: String, var nBgClr: Int)
        var appList:ArrayList<AppInfo> = arrayListOf()
        var sPathLaunch:String = ""
        fun SaveAppList() {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = documentBuilderFactory.newDocumentBuilder()
            val document = docBuilder.newDocument()

            val root = document.createElement("root")
            document.appendChild(root)

            val element = document.createElement("app_list")
            element.setAttribute("size","${appList.size}")
            for(i in 0..<appList.size){
                val element1 = document.createElement("app$i")
                element1.setAttribute("name", appList[i].app_name)
                element1.setAttribute("package_name", appList[i].app_package_name)
                element1.setAttribute("BgClr","${appList[i].nBgClr}")
                element.appendChild(element1)
            }
            root.appendChild(element)

            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            val source = DOMSource(document)
            val result = StreamResult(sPathLaunch)
            transformer.transform(source, result)
        }

        fun LoadAppList() {
            val documentBuilderFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = documentBuilderFactory.newDocumentBuilder()
            val document = docBuilder.parse(File(sPathLaunch))
            document.documentElement.normalize()
            val root = document.documentElement

            appList.clear()
            val element = root.getElementsByTagName("app_list")
            for(i in 0 ..< element.item(0).childNodes.length) {
                val attributes = element.item(0).childNodes.item(i).attributes
                val appInfo = AppInfo(attributes.getNamedItem("name").nodeValue
                    ,attributes.getNamedItem("package_name").nodeValue
                    ,attributes.getNamedItem("BgClr").nodeValue.toInt())
                appList.add(appInfo)
            }
        }
    }

    private var timerTaskChkParam: TimerTask? = null
    private var context: MainActivity? = null

    private var sPathPWD: String = ""
    private var sPathResetPWD: String = ""

    private var nInputPwdErrCount: Long = 0
    private var nNextInputPwdTm: Long = 0


    private var aryPasswordName = Array<String>(10) { "" }

    private var nTrailerLogoClickCount: Long = 0
    private var nTrailerLogoClickTm: Long = 0

    private val ChangePassword = "ChangePassword"
    private val Update         = "Update"
    private val ConfigMenu     = "ConfigMenu"

    private var itemList = ArrayList<GridAdapter.ButtonItem>()
    private var viewPager: ViewPager2? = null
    private var dotsLayout: LinearLayout? = null
    private var itemHeightPx: Int = 0
    private var buttonsPerPage: Int = 4
    private var pageCount: Int = 1

    private var dlgMsg: AlertDialog? = null

    private var bInit: Boolean = false

    // ── MQTT broadcast receivers ──────────────────────────────────────────────

    /**
     * Receives ACTION_HOUSEKEEPING_COMPLETE after TmsMqttManager finishes a
     * server-triggered MQTT HouseKeeping (hkreq/hkresp) cycle.  Only handles the
     * UI refresh (logos + button grid); the actual downloads run in TmsMqttManager
     * and are therefore not gated on this Activity being in the foreground.
     */
    private val housekeepingCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Housekeeping complete — refreshing UI"))
            LoadLogo()
            ReadCfg()
        }
    }

    /** Receives ACTION_BLOCK_TERMINAL — BlockedActivity is already launched by TmsNotificationHandler. */
    private val blockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Terminal BLOCK received from TMS"))
            writeLog("Terminal blocked by TMS operator")
        }
    }

    /** Receives ACTION_UNBLOCK_TERMINAL — BlockedActivity is already finished by TmsNotificationHandler. */
    private val unblockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Terminal UNBLOCK received from TMS"))
            writeLog("Terminal unblocked by TMS operator")
        }
    }

    /** Receives ACTION_TERMINAL_NOT_REGISTERED — shows a blocking dialog and halts reconnection. */
    private val notRegisteredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Terminal not registered in TMS"))
            writeLog("MQTT auth failed: terminal $vg_sSN not registered in TMS")
            showNotRegisteredDialog()
        }
    }

    /** Receives ACTION_SHOW_OPERATOR_MESSAGE — shows the server-sent text in a dialog immediately. */
    private val operatorMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(EXTRA_MESSAGE_TEXT) ?: return
            Logd(Exception("Operator message received: $msg"))
            writeLog("Operator message: $msg")
            ShowAlertMsg(msg, getString(R.string.msg_from_acquirer))
        }
    }

    /**
     * Receives ACTION_LAUNCHER_CONFIG_UPDATED after LauncherConfigManager has downloaded
     * and applied a new TMS-assigned launcher configuration.  Reloads the app list and
     * rebuilds the button grid so the user sees the updated set of apps immediately.
     */
    private val launcherConfigUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Launcher config updated — refreshing button grid"))
            if (sPathLaunch.isNotBlank() && File(sPathLaunch).exists()) {
                LoadAppList()
            }
            // ReadCfg instead of LoadBtn: ensures ConfigMenu is always injected into
            // the list before the grid is drawn, even though the LauncherConfig JSON
            // from the server does not include system buttons.
            ReadCfg()
            // Apply bar visibility and window colors from updated TMS theme
            runOnUiThread {
                UpdateBgClr()
                ApplyDeviceBars()
            }
        }
    }

    /** Refreshes the launcher grid whenever an app is installed, uninstalled, or updated. */
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Logd(Exception("Package change: ${intent?.action} / ${intent?.data}"))
            runOnUiThread { LoadBtn() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Logd(Exception(""))
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_main)
        observeTmsTaskStatus()

        vg_sSN = APIProxy.getDeviceEngine(this).deviceInfo.sn
        Logd(Exception("S/N=${vg_sSN}"))

        initSystemService()
        aryPasswordName[0] = "Admin"

        onBackPressedDispatcher.addCallback(this , object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Logd(Exception("handleOnBackPressed"))
            }
        })

        context = this

        UpdateBgClr()

        viewPager = findViewById(R.id.viewPagerFuncs)
        dotsLayout = findViewById(R.id.dotsIndicator)

        viewPager!!.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position, pageCount)
            }
        })

        viewPager!!.doOnLayout {
            val rows = resources.getInteger(R.integer.grid_rows)
            itemHeightPx = it.height / rows
            buttonsPerPage = 2 * rows
            buildPages()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            checkForPermission()
        }, 100)

        Handler(Looper.getMainLooper()).postDelayed({
            checkRemoteControlSetup()
        }, 3_000)
    }

    override fun onDestroy() {
        Logd(Exception(""))
        timerTaskChkParam?.cancel()
        timerTaskChkParam = null
        instance = null

        super.onDestroy()
        // exitProcess(0) removed: the MQTT foreground service (stopWithTask=false) must
        // survive MainActivity's lifecycle so block notifications can be delivered while
        // any other app is in the foreground. exitProcess would kill the service process.
    }

    override fun onResume() {
        super.onResume()
        Logd(Exception(""))

        // Register MQTT broadcast receivers while the Activity is visible.
        val flag = ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(this, housekeepingCompleteReceiver,  IntentFilter(ACTION_HOUSEKEEPING_COMPLETE), flag)
        ContextCompat.registerReceiver(this, launcherConfigUpdatedReceiver, IntentFilter(ACTION_LAUNCHER_CONFIG_UPDATED), flag)
        ContextCompat.registerReceiver(this, blockReceiver,               IntentFilter(ACTION_BLOCK_TERMINAL), flag)
        ContextCompat.registerReceiver(this, unblockReceiver,         IntentFilter(ACTION_UNBLOCK_TERMINAL), flag)
        ContextCompat.registerReceiver(this, notRegisteredReceiver,   IntentFilter(ACTION_TERMINAL_NOT_REGISTERED), flag)
        ContextCompat.registerReceiver(this, operatorMessageReceiver, IntentFilter(ACTION_SHOW_OPERATOR_MESSAGE), flag)
        ContextCompat.registerReceiver(this, packageChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)

        if (bInit) ReadCfg()   // ensures ConfigMenu is always present, then calls LoadBtn()
        else LoadBtn()         // before Init: sPathLaunch blank, just refresh grid
        //getAllApps()

        // If the terminal is blocked and BlockedActivity is not already on screen
        // (e.g. the user navigated away via the recents button), relaunch it.
        // This runs on every resume so the block screen cannot be bypassed by
        // switching tasks and returning to the launcher.
        val store = TmsCredentialStore(this)
        if (store.isBlocked() && BlockedActivity.instance == null) {
            startActivity(Intent(this, BlockedActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister to avoid leaking the receivers when the Activity is not visible.
        try { unregisterReceiver(housekeepingCompleteReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(launcherConfigUpdatedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(blockReceiver)                 } catch (_: Exception) {}
        try { unregisterReceiver(unblockReceiver)           } catch (_: Exception) {}
        try { unregisterReceiver(notRegisteredReceiver)     } catch (_: Exception) {}
        try { unregisterReceiver(operatorMessageReceiver)   } catch (_: Exception) {}
        try { unregisterReceiver(packageChangeReceiver)     } catch (_: Exception) {}
    }

    fun BtnClick(view: View) {
        Logd(Exception(view.toString()))
        when(view.id){
            R.id.imgTrailerLogo -> TrailerLogo()
        }
    }

    @Suppress("DEPRECATION")
    private fun checkForPermission() {
        if (ChkExternalStorage()) {
            Init()
        } else {
            if(3 < vg_nTryConut) {
                finish()
                return
            }
            vg_nTryConut++;
            val uri = "package:${BuildConfig.APPLICATION_ID}".toUri()
            val intent = Intent(GetStorageAction(), uri)
            context?.startActivityForResult(intent, PERMISSION_REQUEST_CODE);
        }
    }
    private fun Init() {
        if(true == bInit)
            return//目前不明原因跑2次
        bInit = true
        vg_sLogPath = getExternalFilesDir("").toString()+"/Log.txt"
        writeLog("Version : ${BuildConfig.VERSION}")

        vg_sIntrenalPath = getDir("data", MODE_PRIVATE).toString()
        // Use app-private external files dir so downloaded APKs are removed when the
        // app is uninstalled.  Maps to /sdcard/Android/data/<pkg>/files/Download/TMS/
        // which is already covered by the <external-files-path> entry in file_paths.xml.
        vg_sExtrenalPath = File(getExternalFilesDir(null), "Download/TMS").absolutePath
        Logd(Exception("vg_sExtrenalPath=$vg_sExtrenalPath"))
        File(vg_sExtrenalPath).mkdirs()

        vg_sXtmsParam = Environment.getExternalStorageDirectory().toString() + "/xtms_param/app/${packageName}/Launcher_Config.JSON"
        Logd(Exception("vg_sXtmsParam=${vg_sXtmsParam}"))

        //pwd
        sPathPWD = "$vg_sIntrenalPath/Password.xml"
        sPathResetPWD = getExternalFilesDir("").toString()+"/ResetPassword"
        Logd(Exception("sPathResetPWD=$sPathResetPWD"))
        ResetPWD()
        CreateDefaultPWD()
        SuperPwdStore.init(this)

        chkAssetsCopy(this)

        TMSFunc.ChkParamChange()

        // ── MQTT provisioning (safety net — XtmsAgentApplication.onCreate normally does this) ──
        // If XtmsAgentApplication could not obtain the SN (Nexgo SDK not ready at Application level),
        // vg_sSN is now set so we provision here before starting the service.
        val store = TmsCredentialStore(this)
        if (store.loadTermId().isNullOrBlank() && vg_sSN.isNotBlank()) {
            store.saveTermId(vg_sSN)
            Logd(Exception("TermID provisioned from MainActivity: $vg_sSN"))
            writeLog("TermID provisioned: $vg_sSN")
        }
        // Always update broker host from the parsed config (ChkParamChange already ran above).
        // XtmsAgentApplication may have stored a stale default before the JSON was read.
        // Save to store so TmsMqttService.onCreate() picks up the correct host on start.
        // Do NOT call TmsMqttManager directly here — the service owns initialization;
        // double-initialize causes parallel connection races.
        val configHost = TMSFunc.tmsCfg.mqttHost
        val cachedHost = store.loadBrokerHost()
        store.saveBrokerHost(configHost)
        Logd(Exception("Broker host set from config: $configHost (was: $cachedHost)"))
        TmsMqttService.start(this)

        // Reshow the block screen if the terminal was blocked before this process started
        // (e.g. after a reboot or app update). BlockedActivity persists this state.
        if (store.isBlocked()) {
            Logd(Exception("Terminal is blocked — relaunching BlockedActivity"))
            writeLog("Startup: terminal blocked — showing BlockedActivity")
            startActivity(Intent(this, BlockedActivity::class.java).apply {
                // FLAG_ACTIVITY_NEW_TASK is required because BlockedActivity has
                // taskAffinity="" — it lives in a separate task from MainActivity.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        UpdateBgClr()

        //launch
        sPathLaunch =  "$vg_sIntrenalPath/LaunchAPP.xml"
        if (File(sPathLaunch).exists()) {
            LoadAppList()
        }
        else {
            //default
            appList.add(AppInfo(getString(R.string.config_menu_title), ConfigMenu, stTheme.foreground_color.toColorInt()))
            SaveAppList()
        }

        LoadLogo()
        LoadBtn()
        ChkVer(false)

        timerTaskChkParam = Timer().schedule(1000, 1000) {
            if(TMSFunc.ChkParamChange()) {
                runOnUiThread {
                    UpdateBgClr()
                    ApplyDeviceBars()
                    ReadCfg()
                }
            }
            if(vg_sShowMsg.isNotEmpty()) {
                // Used by InstallReceiver to surface APK install results.
                ShowAlertMsg(vg_sShowMsg)
                vg_sShowMsg = ""
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == PERMISSION_REQUEST_CODE){
            checkForPermission()
        }
    }

    fun ChkExternalStorage():Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            Environment.isExternalStorageLegacy()
    }

    fun GetStorageAction():String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        else
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }

    fun chkAssetsCopy(context: Context) {
        //getDir("data", AppCompatActivity.MODE_PRIVATE)
        // Getting package info of this application
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val chkfile = File(vg_sIntrenalPath, info.lastUpdateTime.toString() + ".assets_chk")
        if (!chkfile.exists()) {
            val dirfile = File(vg_sIntrenalPath)
            //deleteDirectory(dirfile)
            dirfile.walk().filter { it.name.endsWith(".assets_chk") }.forEach {
                Logd(Exception(it.path))
                File(it.path).delete()
            }

            copyAssetFiles(context, "")
            Logd(Exception("copyAssetFiles end"))

            if (chkAssetFiles(context, "")) {
                FileOutputStream(chkfile).close() //create empty file
            }
            Logd(Exception("chkAssetFiles end"))
        } else
            Logd(Exception("assets copyed"))
    }

    private fun copyAssetFiles(context: Context, path: String) {
        val list: Array<String>? = context.assets.list(path)

        if (list?.isNotEmpty() == true) {
            // This is a folder
            Logd(Exception("$path is dir"))
            val outFile = File(vg_sIntrenalPath, path)
            outFile.mkdirs()
            for (file in list) {
                var filepath = "$path/$file"
                if (path.isEmpty())
                    filepath = file
                copyAssetFiles(context, filepath)
            }
        } else {
            Logd(Exception("$path is file"))
            val instream = context.assets.open(path)
            val outFile = File(vg_sIntrenalPath, path)
            val outstream = FileOutputStream(outFile)
            val buffer = ByteArray(1024)
            var read: Int
            while (instream.read(buffer).also { read = it } != -1) {
                outstream.write(buffer, 0, read)
            }
            instream.close()
            outstream.close()
        }
    }

    private fun chkAssetFiles(context: Context, path: String): Boolean {
        val list: Array<String>? = context.assets.list(path)

        if (list?.isNotEmpty() == true) {
            // This is a folder
            for (file in list) {
                var filepath = "$path/$file"
                if (path.isEmpty())
                    filepath = file
                if (!chkAssetFiles(context, filepath))
                    return false
            }
        } else {
            var chk = false
            val instream = context.assets.open(path)
            val outFile = File(vg_sIntrenalPath, path)
            val outstream = FileInputStream(outFile)
            if (instream.available() == outstream.available())
                chk = true
            instream.close()
            outstream.close()

            Logd(Exception("$path check $chk"))
            return chk;
        }
        return true
    }

    fun ChkVer(@Suppress("UNUSED_PARAMETER") bShowReInstallApk: Boolean) {
        writeLog("Version check requested via MQTT")
        TmsMqttManager.publishVersionRequest()
        LoadLogo()
        ReadCfg()
    }

    fun LaunchApp(packageName:String) {
        if (packageName.isEmpty())
            return

        val launchIntent: Intent? =
            packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            //launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            Logd(Exception("Launch ${launchIntent.`package`}"))
            writeLog("Launch ${launchIntent.`package`}")
            startActivity(launchIntent)
        }
    }

    protected val hexArray = "0123456789ABCDEF".toCharArray()

    fun bytes2Hex(bytes: ByteArray?): String {
        if (bytes == null) {
            return ""
        }
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray.get(v ushr 4)
            hexChars[j * 2 + 1] = hexArray.get(v and 0x0F)
        }
        return String(hexChars)
    }

    fun StrSHA256(str:String):String {
        return bytes2Hex(
            MessageDigest
            .getInstance("SHA-256")
            .digest(str.toByteArray()))
    }

    fun ResetPWD() {
        var file = File(sPathResetPWD)
        if(!file.exists())
            return
        file.delete()

        file = File(sPathPWD)
        if(file.exists())
            file.delete()
        Logd(Exception("reset password"))
        writeLog("reset password")
    }

    fun CreateDefaultPWD() {
        val file = File(sPathPWD)
        if(!file.exists())
        {
            val root = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><root/>"
            file.writeText(root)
        }

        if(!ChkPwdExist(0)) {
            SavePwd(0, "22687075", "27071287")
            Logd(Exception("set ${aryPasswordName[0]} password to default"))
            writeLog("set ${aryPasswordName[0]} password to default")
        }
        /*
        if(!ChkPwdExist(1)) {
            SavePwd(1, "1234567", "8901234")
            Logd(Exception("set pwd1 to default"))
            writeLog("set pwd1 to default")
        }*/
    }

    fun ChkPwdExist(nIdx:Int):Boolean {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = docBuilder.parse(File(sPathPWD))
        document.documentElement.normalize()
        val root = document.documentElement
        val element = root.getElementsByTagName("pwd$nIdx")
        if(0 == element.length)
            return false
        return true
    }

    fun SavePwd(nIdx:Int, p0:String, p1:String) {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = docBuilder.parse(File(sPathPWD))
        document.documentElement.normalize()
        val root = document.documentElement

        val element = root.getElementsByTagName("pwd$nIdx")
        if(0 == element.length) {
            val pwd0 = document.createElement("pwd$nIdx")
            pwd0.setAttribute("p0", StrSHA256(p0))
            pwd0.setAttribute("p1", StrSHA256(p1))
            root.appendChild(pwd0)
        }
        else {
            for (j in 0 until element.item(0).attributes.length) {
                val attribute = element.item(0).attributes.item(j)
                if (attribute.nodeName == "p0")
                    attribute.nodeValue = StrSHA256(p0)
                if (attribute.nodeName == "p1")
                    attribute.nodeValue = StrSHA256(p1)
            }
        }

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        val source = DOMSource(document)
        val result = StreamResult(sPathPWD)

        transformer.transform(source, result)
    }

    fun InputPassword(nIdx:Int, onDialogDismiss: () -> Unit){
        if(BuildConfig.DEBUG) { onDialogDismiss(); return }
        if(SystemClock.elapsedRealtime() < nNextInputPwdTm ) {
            val nWaitMS = nNextInputPwdTm - SystemClock.elapsedRealtime()
            ShowAlertMsg("${getString(R.string.pls_wait)} ${nWaitMS/1000} ${getString(R.string.seconds)}")
            return
        }

        var vl_nPass = -1

        val view = layoutInflater.inflate(R.layout.pwd_input, null)
        view.findViewById<TextView>(R.id.title).text = getString(R.string.input_pwd)
        val edit1 = view.findViewById<EditText>(R.id.password1)
        val edit2 = view.findViewById<EditText>(R.id.password2)
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder
            .setView(view)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                if(ChkPwd(edit1.text.toString(),nIdx,0) && ChkPwd(edit2.text.toString(),nIdx,1))
                    vl_nPass = 1;
                else
                    vl_nPass = 0
                dialog.dismiss()  // Dismisses the dialog
            }
            .setNeutralButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()  // Dismisses the dialog
            }
        val dialog: AlertDialog = builder.create()
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener() {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            dialog.getButton(Dialog.BUTTON_NEUTRAL).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        dialog.setOnDismissListener {
            // This is called after the dialog is dismissed
            if(1 == vl_nPass) {
                nInputPwdErrCount = 0
                nNextInputPwdTm = 0
                onDialogDismiss()
            }
            else if(0 == vl_nPass) {
                nInputPwdErrCount++
                if(3 <= nInputPwdErrCount) {
                    nNextInputPwdTm = if (18 <= nInputPwdErrCount)
                        18 * 10 * 1000 + SystemClock.elapsedRealtime()
                    else
                        nInputPwdErrCount * 10 * 1000 + SystemClock.elapsedRealtime()
                }
                ShowAlertMsg(getString(R.string.pwd_err))
            }
        }
        dialog.show()
    }

    fun ShowAlertMsg(sMsg: String, title: String = "") {
        this@MainActivity.runOnUiThread(java.lang.Runnable {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            if (title.isNotEmpty()) builder.setTitle(title)
            builder
                .setMessage(sMsg)
                .setPositiveButton(R.string.ok){ dialog, _ ->
                    dialog.dismiss()  // Dismisses the dialog
                }

            val dialog: AlertDialog = builder.create()
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnShowListener() {
                dialog.findViewById<TextView>(android.R.id.message).setTextSize(TypedValue.COMPLEX_UNIT_PT, 10f)
                dialog.getButton(Dialog.BUTTON_POSITIVE)
                    .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            dialog.show()
        })
    }

    private fun showNotRegisteredDialog() {
        runOnUiThread {
            val msg = "Terminal: $vg_sSN is not registered in the TMS Server."
            val builder = AlertDialog.Builder(this)
            builder
                .setTitle("TMS Registration Error")
                .setMessage(msg)
                .setPositiveButton(R.string.retry) { dialog, _ ->
                    dialog.dismiss()
                    TmsMqttManager.resume()
                }
            val dialog = builder.create()
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnShowListener {
                dialog.findViewById<TextView>(android.R.id.message)
                    .setTextSize(TypedValue.COMPLEX_UNIT_PT, 10f)
                dialog.getButton(Dialog.BUTTON_POSITIVE)
                    .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            dialog.show()
        }
    }

    fun WaitMsg(sMsg: String) {
        this@MainActivity.runOnUiThread(java.lang.Runnable {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setMessage(sMsg)
            dlgMsg = builder.create()
            dlgMsg!!.setCancelable(false)
            dlgMsg!!.setCanceledOnTouchOutside(false)
            dlgMsg!!.setOnShowListener() {
                dlgMsg!!.findViewById<TextView>(android.R.id.message).setTextSize(TypedValue.COMPLEX_UNIT_PT, 15f)
            }
            dlgMsg!!.show()
        })
    }

    fun getAppNameFromApk(): String? {
        var apkPath = ""
        Path(vg_sExtrenalPath).listDirectoryEntries().forEach(){it ->
            if(it.name.contains(APP_HEAD)) {
                apkPath = "${vg_sExtrenalPath}/${it.name}"
            }
        }
        val packageManager = context?.packageManager
        val packageInfo = packageManager?.getPackageArchiveInfo(apkPath, 0)

        appList.forEach { it ->
            if(it.app_package_name == packageInfo?.packageName)
                return it.app_name
        }

        return packageInfo?.packageName
    }

    fun ReInstallApkAsk() {
        this@MainActivity.runOnUiThread(java.lang.Runnable {
            val sMsg = "${getString(R.string.re_install)} ${getAppNameFromApk()}"
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder
                .setMessage(sMsg)
                .setPositiveButton(R.string.ok){ dialog, _ ->
                    dialog.dismiss()  // Dismisses the dialog
                    InstallApp()
                }
                .setNeutralButton(R.string.cancel){ dialog, _ ->
                    dialog.dismiss()  // Dismisses the dialog
                }
            val dialog: AlertDialog = builder.create()
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnShowListener() {
                dialog.findViewById<TextView>(android.R.id.message).setTextSize(TypedValue.COMPLEX_UNIT_PT, 10f)
                dialog.getButton(Dialog.BUTTON_POSITIVE)
                    .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                dialog.getButton(Dialog.BUTTON_NEGATIVE)
                    .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                dialog.getButton(Dialog.BUTTON_NEUTRAL)
                    .setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            dialog.show()
        })
    }

    fun ChkPwd(sPwd:String, nIdx:Int, nPart:Int):Boolean {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = documentBuilderFactory.newDocumentBuilder()
        val document = docBuilder.parse(File(sPathPWD))
        document.documentElement.normalize()
        val root = document.documentElement

        //super user
        if(sPwd == GetSuperUser(SuperPwdStore.loadSeed(this, nPart)))
            return true

        val element = root.getElementsByTagName("pwd$nIdx")
        for (j in 0 until (element.item(0)?.attributes?.length ?: 0)) {
            val attribute = element.item(0).attributes.item(j)
            if(attribute.nodeName == "p$nPart" && attribute.nodeValue == StrSHA256(sPwd)) {
                return true
            }
        }
        return false
    }

    fun ChangePwd(nIdx:Int){
        //PCI 密碼規定 : 猜中的機率要 < 1/10000000 (至少7位數)，每分鐘試錯 < 1000
        val view = layoutInflater.inflate(R.layout.pwd_change, null)
        view.findViewById<TextView>(R.id.title).text = getString(R.string.chg_pwd)

        val new1 = view.findViewById<EditText>(R.id.newpassword1)
        val new2 = view.findViewById<EditText>(R.id.newpassword2)
        val renew1 = view.findViewById<EditText>(R.id.renewpassword1)
        val renew2 = view.findViewById<EditText>(R.id.renewpassword2)
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder
            .setView(view)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                if(7 > new1.text.toString().length || 7 > new2.text.toString().length) {
                    ShowAlertMsg(getString(R.string.chg_pwd_err_no_7))
                }
                else if(new1.text.toString() == new2.text.toString()) {
                    ShowAlertMsg(getString(R.string.chg_pwd_err_same))
                }
                else if(new1.text.toString() == renew1.text.toString()
                    && new2.text.toString() == renew2.text.toString()){
                    SavePwd(nIdx,new1.text.toString(),new2.text.toString())
                    Logd(Exception("Change ${aryPasswordName[nIdx]} Password OK"))
                    writeLog("Change ${aryPasswordName[nIdx]} Password OK")
                    ShowAlertMsg(getString(R.string.chg_pwd_ok))
                }
                else {
                    ShowAlertMsg(getString(R.string.chg_pwd_err_no_match))
                }
                dialog.dismiss()  // Dismisses the dialog
            }
            .setNeutralButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()  // Dismisses the dialog
            }
        val dialog: AlertDialog = builder.create()
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener() {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            dialog.getButton(Dialog.BUTTON_NEUTRAL).setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        dialog.show()
    }

    fun GetSuperUser(sPwd:String):String {
        val sDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Calendar.getInstance().time)
        val sPwdSHA = StrSHA256("$sDate$sPwd").substring(0,8)//取前8碼
        Logd(Exception("sPwdSHA=${sPwdSHA}"))
        var nPwd = sPwdSHA.toLong(16)
        Logd(Exception("nPwd=${nPwd}"))
        nPwd %= 100000000

        return String.format("%08d",nPwd)
    }

    fun getAllApps() {
        context?.packageManager?.getInstalledPackages(0)?.forEach { info ->
            info.applicationInfo?.let { appInfo ->
                if (0 == (appInfo.flags and ApplicationInfo.FLAG_SYSTEM))
                    Logd(Exception("User PackageName=${appInfo.packageName}"))
                else
                    Logd(Exception("System PackageName=${appInfo.packageName}"))
            }
        }
    }

    fun LoadLogo(){
        findViewById<TextView>(R.id.txtBrandName).text = ""

        val brandLogoFile = File("$vg_sIntrenalPath/cfg/brandlogo.png")
        val brandDrawable = Drawable.createFromPath(brandLogoFile.absolutePath)
            ?: AppCompatResources.getDrawable(this, R.drawable.logo_color)
        findViewById<ImageView>(R.id.imgBrandLogo).setImageDrawable(brandDrawable)

        val trailerLogoFile = File("$vg_sIntrenalPath/cfg/uiclogo.png")
        val trailerDrawable = Drawable.createFromPath(trailerLogoFile.absolutePath)
        findViewById<ImageView>(R.id.imgTrailerLogo).setImageDrawable(trailerDrawable)
    }

    fun LoadBtn(){
        val blockUnknown = getSharedPreferences("tms_launcher", Context.MODE_PRIVATE)
            .getBoolean("blockUnknownApps", true)

        val configEntry = appList.filter { it.app_package_name == ConfigMenu || it.app_package_name == Settings.ACTION_SETTINGS }
        val tmsApps     = appList.filter { it.app_package_name != ConfigMenu && it.app_package_name != Settings.ACTION_SETTINGS }

        val displayList: List<Companion.AppInfo> = if (!blockUnknown) {
            val knownPackages = appList.map { it.app_package_name }.toHashSet()
            val pm = packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val unknownApps = pm.queryIntentActivities(launchIntent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { pkg -> pkg != packageName && pkg !in knownPackages && pkg !in BLOCKED_PACKAGES }
                .map { pkg ->
                    val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                                  catch (_: Exception) { pkg }
                    Companion.AppInfo(appName, pkg, unknownAppColor(pkg))
                }
            tmsApps + unknownApps + configEntry
        } else {
            tmsApps + configEntry
        }

        itemList.clear()
        displayList.forEach(){ it ->
            var sAppName = it.app_name
            val drawable: Drawable? = try {
                val appInfo = packageManager.getApplicationInfo(it.app_package_name, 0)
                sAppName = packageManager.getApplicationLabel(appInfo).toString()
                packageManager.getApplicationIcon(it.app_package_name)
            } catch (_: PackageManager.NameNotFoundException) {
                sAppName = when (it.app_package_name) {
                    ConfigMenu          -> getString(R.string.config_menu_title)
                    Settings.ACTION_SETTINGS -> getString(R.string.config_menu_title)  // legacy entries
                    ChangePassword      -> getString(R.string.chg_pwd)
                    Update              -> getString(R.string.update)
                    else -> return@forEach  // Real app package not installed — hide the button
                }
                when (it.app_package_name) {
                    ConfigMenu, Settings.ACTION_SETTINGS ->
                        ContextCompat.getDrawable(this, R.drawable.settings)
                    ChangePassword -> ContextCompat.getDrawable(this, R.drawable.changepwd)
                    Update         -> ContextCompat.getDrawable(this, R.drawable.update)
                    else -> null
                }
            }

            val btnItme = GridAdapter.ButtonItem(sAppName, it.app_package_name, it.nBgClr, drawable){
                when (it.app_package_name) {
                    // Config menu — no password, opens the submenu screen
                    ConfigMenu, Settings.ACTION_SETTINGS -> {
                        Logd(Exception("open ConfigMenuActivity"))
                        writeLog("open ConfigMenuActivity")
                        startActivity(Intent(this, ConfigMenuActivity::class.java))
                    }
                    ChangePassword -> {
                        InputPassword(0) { ChangePwd(0) }
                    }
                    Update -> {
                        ChkVer(true)
                    }
                    else -> {
                        LaunchApp(it.app_package_name)
                    }
                }
            }
            itemList.add(btnItme)
        }

        if (itemHeightPx > 0) buildPages()
    }

    // Deterministic muted color for apps not in the TMS config list.
    // Picks from a dark palette by package name hash so each app gets a stable color.
    private fun unknownAppColor(packageName: String): Int {
        val palette = intArrayOf(
            0xFF424242.toInt(), // charcoal
            0xFF37474F.toInt(), // blue-grey dark
            0xFF4A235A.toInt(), // deep purple dark
            0xFF1B5E20.toInt(), // dark green
            0xFF0D47A1.toInt(), // dark blue
            0xFF4E342E.toInt(), // brown dark
            0xFF006064.toInt(), // teal dark
            0xFF4A148C.toInt(), // purple dark
        )
        return palette[Math.abs(packageName.hashCode()) % palette.size]
    }

    fun TrailerLogo() {
        if(nTrailerLogoClickTm > SystemClock.elapsedRealtime())
            nTrailerLogoClickCount++
        else
            nTrailerLogoClickCount = 1
        nTrailerLogoClickTm = SystemClock.elapsedRealtime() + 1000;
        if(3 <= nTrailerLogoClickCount) {
            nTrailerLogoClickCount = 0
            ShowAlertMsg("Version : ${BuildConfig.VERSION}")
        }
    }

    fun ReadCfg() {
        // App list managed via LauncherConfig JSON delivered over MQTT.
        // ReadCfg enforces system-button invariants and refreshes the UI.

        // Password buttons live in ConfigMenuActivity — remove them from the main screen
        // in case they were saved from an older version of the app.
        appList.removeAll { it.app_package_name == ChangePassword || it.app_package_name == "ChangeSuperPassword" }

        // Config button always last — remove then re-append so position is correct
        // regardless of what LoadAppList or LauncherConfig put in the list.
        appList.removeAll { it.app_package_name == ConfigMenu || it.app_package_name == Settings.ACTION_SETTINGS }
        appList.add(AppInfo(getString(R.string.config_menu_title), ConfigMenu, stTheme.foreground_color.toColorInt()))

        if (sPathLaunch.isNotBlank()) SaveAppList()
        LoadBtn()
    }

    private fun checkRemoteControlSetup() {
        if (one.globalconnect.xtmsagent.remote.RemoteControlAccessibilityService.isEnabled(this)) return
        AlertDialog.Builder(this)
            .setTitle("Remote Control Setup")
            .setMessage(
                "The Remote Control accessibility service is not enabled.\n\n" +
                "Tap \"Open Settings\", find xTMSAgent → Remote Control, and turn the switch on " +
                "to allow unattended screen sharing from the TMS."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val component = android.content.ComponentName(
                    this,
                    one.globalconnect.xtmsagent.remote.RemoteControlAccessibilityService::class.java
                ).flattenToString()
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    val args = android.os.Bundle().apply {
                        putString(":settings:fragment_args_key", component)
                    }
                    putExtra(":settings:show_fragment_args", args)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun observeTmsTaskStatus() {
        val strip = findViewById<TextView>(R.id.tmsNotifStrip)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(TmsTaskStatus.taskOverride, TmsTaskStatus.connection) { task, connection ->
                    Pair(task, connection)
                }.collect { (task, connection) ->
                    if (task != null) {
                        showTmsStrip(strip, task, taskStatusColor(task))
                    } else if (shouldShowTmsConnectionStatus(
                            connection,
                            LauncherConfigManager.isConfigApplied(this@MainActivity),
                        )
                    ) {
                        showTmsStrip(strip, connection.text, connectionStatusColor(connection.severity))
                    } else {
                        hideTmsStrip(strip)
                    }
                }
            }
        }
    }

    private fun taskStatusColor(msg: String): Int =
        when {
            msg.startsWith("Downloading", ignoreCase = true) -> 0xFFE65100.toInt()
            msg.startsWith("Installing",  ignoreCase = true) -> 0xFF1565C0.toInt()
            msg.startsWith("Installed",   ignoreCase = true) -> 0xFF2E7D32.toInt()
            msg.contains("failed",        ignoreCase = true) -> 0xFFB71C1C.toInt()
            else                                             -> 0xFF424242.toInt()
        }

    private fun connectionStatusColor(severity: TmsStatusSeverity): Int =
        when (severity) {
            TmsStatusSeverity.CONNECTED -> 0xFF2E7D32.toInt()
            TmsStatusSeverity.CONNECTING -> 0xFF1565C0.toInt()
            TmsStatusSeverity.WARNING -> 0xFFE65100.toInt()
            TmsStatusSeverity.ERROR -> 0xFFB71C1C.toInt()
        }

    private fun showTmsStrip(strip: TextView, msg: String, bg: Int) {
        strip.setBackgroundColor(bg)
        strip.text = msg
        if (strip.visibility != View.VISIBLE) {
            strip.visibility = View.VISIBLE
            strip.post {
                strip.translationY = -strip.height.toFloat()
                strip.alpha = 1f
                strip.animate().translationY(0f).setDuration(250).start()
            }
        }
    }

    private fun hideTmsStrip(strip: TextView) {
        if (strip.visibility == View.VISIBLE) {
            strip.animate()
                .translationY(-strip.height.toFloat())
                .setDuration(250)
                .withEndAction { strip.visibility = View.GONE; strip.translationY = 0f }
                .start()
        }
    }

    fun UpdateBgClr() {
        val bgColor = stTheme.background_color.toColorInt()
        findViewById<ConstraintLayout>(R.id.recyclerView_logo).setBackgroundColor(bgColor)
        findViewById<ConstraintLayout>(R.id.recyclerView_logo_t).setBackgroundColor(bgColor)
        viewPager?.setBackgroundColor(bgColor)
        @Suppress("DEPRECATION")
        window.statusBarColor = stTheme.status_bar_color.toColorInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = stTheme.navigation_bar_color.toColorInt()
    }

    fun ApplyDeviceBars() {
        val platform = try {
            APIProxy.getDeviceEngine(this).platform
        } catch (e: Exception) {
            Logd(Exception("ApplyDeviceBars: getDeviceEngine failed: ${e.message}"))
            return
        }

        try {
            if (stTheme.enable_control_bar) platform.enableControlBar() else platform.disableControlBar()
        } catch (e: Exception) {
            Logd(Exception("enableControlBar failed: ${e.message}"))
        }

        try {
            if (stTheme.enable_navigation_bar) platform.showNavigationBar() else platform.hideNavigationBar()
        } catch (e: Exception) {
            Logd(Exception("showNavigationBar failed: ${e.message}"))
        }
    }
    
    private fun buildPages() {
        val vp = viewPager ?: return
        if (itemHeightPx == 0) return

        val pages = mutableListOf<List<GridAdapter.ButtonItem>>()
        val startIndices = mutableListOf<Int>()
        var i = 0
        while (i < itemList.size) {
            val end = minOf(i + buttonsPerPage, itemList.size)
            pages.add(itemList.subList(i, end))
            startIndices.add(i)
            i += buttonsPerPage
        }
        if (pages.isEmpty()) {
            pages.add(emptyList())
            startIndices.add(0)
        }

        pageCount = pages.size
        vp.adapter = LauncherPagerAdapter(pages, itemHeightPx, startIndices)
        updateDots(0, pageCount)
    }

    private fun updateDots(current: Int, count: Int) {
        val layout = dotsLayout ?: return
        layout.removeAllViews()
        if (count <= 1) {
            layout.visibility = View.GONE
            return
        }
        layout.visibility = View.VISIBLE
        val sizePx = resources.getDimensionPixelSize(R.dimen.dot_size)
        val marginPx = resources.getDimensionPixelSize(R.dimen.dot_margin)
        repeat(count) { i ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == current) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
                }
            }
            layout.addView(dot)
        }
    }

    private fun initSystemService() {
        try {
            SystemServiceHelper.getInstance().init(this, object : OnPlatformInitListener {
                override fun onPlatformInitResult(resultCode: Int) {
                    Logd(Exception("SystemServiceHelper ready, resultCode=$resultCode"))
                    applySystemServiceRestrictions()
                    // Re-apply device bars now that the service is bound (avoids NPE on first call)
                    ApplyDeviceBars()
                }
            })
        } catch (e: Exception) {
            Logd(Exception("SystemServiceHelper.init failed: ${e.message}"))
        }
    }

    private fun applySystemServiceRestrictions() {
        val svc = try { SystemServiceHelper.getInstance() } catch (e: Exception) {
            Logd(Exception("SystemServiceHelper.getInstance failed: ${e.message}"))
            return
        }

        // Block notification shade pull-down so operator cannot open USB mode selector.
        // Requires platform signing or system-app install — returns false if denied.
        try {
            val result = svc.getSystemUIManager()?.enableMessageBar(false)
            val msg = "SystemService: enableMessageBar(false) = $result"
            if (result == true) writeLog(msg) else writeLog("WARN: $msg — may need platform signing")
            Logd(Exception(msg))
        } catch (e: Exception) {
            Logd(Exception("enableMessageBar failed: ${e.message}"))
            writeLog("SystemService: enableMessageBar failed: ${e.message}")
        }
    }

    fun InstallApp() {
        val ctx = context ?: return
        Path(vg_sExtrenalPath).listDirectoryEntries().forEach { entry ->
            if (entry.name.contains(APP_HEAD)) {
                try {
                    val apkFile = entry.toFile().also { it.setReadable(true, false) }
                    val platform = APIProxy.getDeviceEngine(ctx).platform
                    platform.installApp(apkFile.absolutePath, object : com.nexgo.oaf.apiv3.OnAppOperatListener {
                        override fun onOperatResult(res: Int) {
                            val success = res == com.nexgo.oaf.apiv3.SdkResult.Success
                            val msg = if (success)
                                "${entry.name} ${getString(R.string.install_success)}"
                            else
                                "${entry.name} ${getString(R.string.install_failure)}: $res"
                            vg_sShowMsg = msg
                            Logd(Exception(msg))
                            writeLog(msg)
                        }
                    })
                } catch (e: Exception) {
                    Logd(Exception("InstallApp SDK error for ${entry.name}: ${e.message}"))
                }
            }
        }
    }
}
