package light.rift.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import light.rift.Key
import light.rift.R
import light.rift.RiftApp
import light.rift.database.DataStore
import light.rift.ktx.Logs
import light.rift.ktx.broadcastReceiver

/**
 * Requests the system VPN permission and then starts the service.
 *
 * When the lock screen is up the request is deferred until the user
 * unlocks (ACTION_USER_PRESENT); otherwise it runs immediately. A denied
 * request shows a toast, then this activity finishes either way.
 */
class VpnRequestActivity : AppCompatActivity() {

    /** Receiver waiting for the unlock broadcast, if one was registered. */
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = getSystemService<KeyguardManager>()!!.isKeyguardLocked
        if (!locked) {
            connect.launch(null)
            return
        }
        unlockReceiver = broadcastReceiver { _, _ -> connect.launch(null) }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    private val connect = registerForActivityResult(StartService()) { granted ->
        if (granted) {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        unlockReceiver?.let { unregisterReceiver(it) }
    }

    /**
     * VPN-permission contract. [getSynchronousResult] starts the service
     * directly when no system consent dialog is needed; otherwise it stashes
     * the consent intent for [createIntent]. The boolean result is true when
     * permission was denied.
     */
    class StartService : ActivityResultContract<Void?, Boolean>() {

        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(
            context: Context,
            input: Void?,
        ): SynchronousResult<Boolean>? {
            if (DataStore.serviceMode == Key.MODE_VPN) {
                val consent = VpnService.prepare(context)
                if (consent != null) {
                    cachedIntent = consent
                    return null
                }
            }
            RiftApp.startService()
            return SynchronousResult(false)
        }

        override fun createIntent(context: Context, input: Void?): Intent =
            cachedIntent!!.also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return if (resultCode == Activity.RESULT_OK) {
                RiftApp.startService()
                false
            } else {
                Logs.e("Failed to start VpnService: $intent")
                true
            }
        }
    }

}
