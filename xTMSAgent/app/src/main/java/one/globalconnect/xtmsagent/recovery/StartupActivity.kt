package one.globalconnect.xtmsagent.recovery

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** HOME/launcher entry point has no normal launcher or vendor initialization. */
class StartupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (StartupRecoveryGuard.inRecovery) {
            startActivity(Intent(this, RecoveryActivity::class.java))
        } else {
            StartupRecoveryGuard.monitorHealthyStartup(applicationContext)
            startActivity(Intent().setClassName(packageName, "one.globalconnect.xtmsagent.MainActivity"))
        }
        finish()
    }
}
