package one.globalconnect.paymentapp.ecr

import android.content.*
import android.os.*
import android.util.Log

object EcrKioskPolicy {
    private var desired = false
    private var messenger: Messenger? = null
    private var binding = false
    private lateinit var app: Context
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger=Messenger(service); send()
        }
        override fun onServiceDisconnected(name: ComponentName) { messenger=null }
        override fun onBindingDied(name: ComponentName) { messenger=null; app.unbindService(this); binding=false; apply(app,desired) }
    }
    private fun send() {
        try { messenger?.send(Message.obtain(null,if(desired) 3 else 4).apply {
            data=Bundle().apply { putString("packageName",app.packageName) }
        }) } catch(e: RemoteException) { Log.w("EcrKiosk","Unable to apply system kiosk policy",e) }
    }
    fun apply(context: Context, locked: Boolean) {
        // Do not clear a device policy owned by another feature on ordinary app startup.
        if (!locked && !desired && !binding) return
        app=context.applicationContext; desired=locked
        if(messenger!=null) { send(); return }
        if(binding) return
        val intent=Intent("one.globalconnect.xtmsagent.APPLICATION_LICENSE_SERVICE")
        val service=app.packageManager.queryIntentServices(intent,0).firstOrNull()?.serviceInfo ?: return
        intent.component=ComponentName(service.packageName,service.name)
        binding=app.bindService(intent,connection,Context.BIND_AUTO_CREATE)
    }
}
