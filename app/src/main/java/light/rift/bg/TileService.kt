package light.rift.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import light.rift.R
import light.rift.RiftApp
import light.rift.aidl.IRiftAppService
import light.rift.database.RiftDatabase
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), RiftConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy {
        Icon.createWithResource(this, R.drawable.ic_service_active)
    }
    private var tapPending = false

    private val connection = RiftConnection(RiftConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: IRiftAppService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = RiftDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    /** 每个服务状态对应的磁贴样子：图标、磁贴状态、文字（不填就用应用名）。 */
    private fun tileLook(state: BaseService.State, profileName: String?): Triple<Icon, Int, String?> {
        return when (state) {
            BaseService.State.Idle -> error("serviceState")
            BaseService.State.Connecting -> Triple(iconBusy, Tile.STATE_ACTIVE, null)
            BaseService.State.Connected -> Triple(iconConnected, Tile.STATE_ACTIVE, profileName)
            BaseService.State.Stopping -> Triple(iconBusy, Tile.STATE_UNAVAILABLE, null)
            BaseService.State.Stopped -> Triple(iconIdle, Tile.STATE_INACTIVE, null)
        }
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            val (icon, tileState, tileLabel) = tileLook(serviceState, profileName)
            this.icon = icon
            state = tileState
            label = tileLabel ?: getString(R.string.app_name)
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) tapPending =
            true else BaseService.State.values()[service.state].let { state ->
            when {
                state.canStop -> RiftApp.stopService()
                state == BaseService.State.Stopped -> RiftApp.startService()
            }
        }
    }
}
