package light.rift

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import light.rift.aidl.IRiftAppService
import light.rift.bg.BaseService
import light.rift.bg.RiftConnection
import light.rift.database.DataStore

@Suppress("DEPRECATION")
class QuickToggleShortcut : Activity(), RiftConnection.Callback {
    private val connection = RiftConnection(RiftConnection.CONNECTION_ID_SHORTCUT)
    private var profileId = -1L

    /** 桌面上长按应用图标新建快捷方式时要返回的结果。 */
    private fun buildShortcutResultIntent(): Intent {
        return ShortcutManagerCompat.createShortcutResultIntent(
            this,
            ShortcutInfoCompat.Builder(this, "toggle")
                .setIntent(
                    Intent(this, QuickToggleShortcut::class.java)
                        .setAction(Intent.ACTION_MAIN)
                )
                .setIcon(
                    IconCompat.createWithResource(
                        this,
                        R.drawable.ic_qu_shadowsocks_launcher
                    )
                )
                .setShortLabel(getString(R.string.quick_toggle))
                .build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            setResult(RESULT_OK, buildShortcutResultIntent())
            finish()
        } else {
            profileId = intent.getLongExtra("profile", -1L)
            connection.connect(this, this)
            if (Build.VERSION.SDK_INT >= 25) {
                getSystemService<ShortcutManager>()!!.reportShortcutUsed(
                    if (profileId >= 0) "shortcut-profile-$profileId" else "toggle"
                )
            }
        }
    }

    override fun onServiceConnected(service: IRiftAppService) {
        val state = BaseService.State.values()[service.state]
        when {
            state.canStop -> {
                if (profileId == DataStore.selectedProxy || profileId == -1L) {
                    RiftApp.stopService()
                } else {
                    DataStore.selectedProxy = profileId
                    RiftApp.reloadService()
                }
            }
            state == BaseService.State.Stopped -> {
                if (profileId >= 0L) DataStore.selectedProxy = profileId
                RiftApp.startService()
            }
        }
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
