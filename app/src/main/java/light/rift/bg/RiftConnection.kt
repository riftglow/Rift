package light.rift.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import light.rift.Action
import light.rift.Key
import light.rift.aidl.IRiftAppService
import light.rift.aidl.IRiftAppServiceCallback
import light.rift.aidl.SpeedDisplayData
import light.rift.aidl.TrafficData
import light.rift.database.DataStore
import light.rift.ktx.runOnMainDispatcher

class RiftConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) : ServiceConnection, IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4

        var restartingApp = false
    }

    interface Callback {
        // smaller IRiftAppServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbSelectorUpdate(id: Long) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun onServiceConnected(service: IRiftAppService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null

    /** 没接回调时直接丢弃；接了就切到主线程再调，避免跨进程回调跑在 binder 线程。 */
    private inline fun postCallback(crossinline call: Callback.() -> Unit) {
        val callback = callback ?: return
        runOnMainDispatcher {
            callback.call()
        }
    }

    private val serviceCallback = object : IRiftAppServiceCallback.Stub() {

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            DataStore.serviceState = s
            postCallback { stateChanged(s, profileName, msg) }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            postCallback { cbSpeedUpdate(stats) }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            postCallback { cbTrafficUpdate(stats) }
        }

        override fun cbSelectorUpdate(id: Long) {
            postCallback { cbSelectorUpdate(id) }
        }

    }

    private var binder: IBinder? = null

    var service: IRiftAppService? = null

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            service?.registerCallback(serviceCallback, id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        this.binder = binder
        val service = IRiftAppService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath) binder.linkToDeath(this, 0)
            check(!callbackRegistered)
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        callback?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() {
        service = null
        callbackRegistered = false
        if (!restartingApp) {
            postCallback { onBinderDied() }
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) try {
            service.unregisterCallback(serviceCallback)
        } catch (_: RemoteException) {
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {
        }   // ignore
        connectionActive = false
        if (listenForDeath) try {
            binder?.unlinkToDeath(this, 0)
        } catch (_: NoSuchElementException) {
        }
        binder = null
        service = null
        callback = null
    }
}
