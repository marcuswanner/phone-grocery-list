package net.wanners.groceries

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class Discovery(context: Context) {
    private val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null

    fun register(port: Int, desiredName: String = "groceries") {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = desiredName
            serviceType = "_http._tcp."
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i("Discovery", "registered ${info.serviceName} on ${info.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w("Discovery", "registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registration?.let {
            runCatching { mgr.unregisterService(it) }
            registration = null
        }
    }
}
