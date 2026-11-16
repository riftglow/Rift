/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <[Email1]>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package light.rift.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import light.rift.RiftApp
import light.rift.aidl.IRiftAppService
import light.rift.bg.BaseService
import light.rift.bg.RiftConnection

/**
 * Launcher shortcut that stops the VPN: binds to the service, and when the
 * connection is up it stops the service if it is in a stoppable state.
 * The shortcut tap is reported to the system on API 25+.
 */
class QuickDisableShortcut : Activity(), RiftConnection.Callback {

    private val serviceLink = RiftConnection(RiftConnection.CONNECTION_ID_SHORTCUT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceLink.connect(this, this)
        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("disable")
        }
    }

    override fun onServiceConnected(service: IRiftAppService) {
        val state = BaseService.State.values()[service.state]
        if (state.canStop) {
            RiftApp.stopService()
        }
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        // No UI to update.
    }

    override fun onDestroy() {
        serviceLink.disconnect(this)
        super.onDestroy()
    }

}
