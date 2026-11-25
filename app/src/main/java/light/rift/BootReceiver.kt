package light.rift

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import light.rift.bg.AutoSpeedTest
import light.rift.bg.SubscriptionUpdater
import light.rift.database.DataStore
import light.rift.ktx.app
import light.rift.ktx.runOnDefaultDispatcher

/**
 * Boot-completed receiver: refreshes the subscription updater, and — when
 * the user asked the VPN to survive reboots — starts the service with the
 * selected profile. Disabled again when persistence is off, so a stray
 * enabled component cannot restart the VPN behind the user's back.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private val componentName: ComponentName by lazy {
            ComponentName(app, BootReceiver::class.java)
        }

        /** Whether the receiver is currently enabled in the package manager. */
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) {
                val state = if (value) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                app.packageManager.setComponentEnabledSetting(
                    componentName, state, PackageManager.DONT_KILL_APP,
                )
            }
    }

    override fun onReceive(context: Context, intent: Intent) {
        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
            // Auto speed test (step ②): re-enqueue the periodic worker after reboot.
            AutoSpeedTest.reconfigure()
        }

        if (!DataStore.persistAcrossReboot) {
            enabled = false
            return
        }

        val mayStartNow = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false // DataStore.directBootAware
            else -> Build.VERSION.SDK_INT < 24 || RiftApp.user.isUserUnlocked
        }
        val shouldStart = mayStartNow && DataStore.selectedProxy > 0
        if (shouldStart) {
            RiftApp.startService()
        }
    }

}
