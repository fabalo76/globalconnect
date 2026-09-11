package one.globalconnect.xtmsagent.nexgo

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText

/**
 * Prevents the Android on-screen keyboard from covering xTMSAgent screens on Nexgo
 * terminals that already provide a physical keypad. The EditText remains focusable,
 * so digits and characters received from the hardware keypad continue to work.
 */
object PhysicalKeypadInputPolicy {
    private val enabled: Boolean by lazy {
        PhysicalKeypadModelResolver.hasPhysicalKeypad(
            modelProperty = AndroidSystemProperties.get("ro.xgd.type"),
            buildModel = Build.MODEL,
        )
    }

    fun install(application: Application) {
        if (!enabled) return

        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    activity.window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                    )
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    fun configure(vararg fields: EditText) {
        if (!enabled) return
        fields.forEach { it.showSoftInputOnFocus = false }
    }
}

object PhysicalKeypadModelResolver {
    fun hasPhysicalKeypad(modelProperty: String?, buildModel: String?): Boolean =
        sequenceOf(modelProperty, buildModel)
            .filterNotNull()
            .map(::normalize)
            .any { it.startsWith("CT20") }

    private fun normalize(value: String): String = value
        .trim()
        .uppercase()
        .filter(Char::isLetterOrDigit)
}
