package light.rift.database

import light.rift.GroupType
import light.rift.bg.SubscriptionUpdater
import light.rift.ktx.applyDefaultValues
import light.rift.ui.AutoBackup

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean,
            // Batch 2 item 6: unsupported entries skipped during
            // import; empty for updaters that do not track them.
            skipped: List<String> = emptyList()
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        DataStore.selectedProxy = 0L
        RiftDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        val entities = RiftDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        RiftDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(RiftDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.userOrder = RiftDatabase.groupDao.nextOrder() ?: 1
        group.id = RiftDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        AutoBackup.schedule()
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        RiftDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        AutoBackup.schedule()
    }

    suspend fun deleteGroup(groupId: Long) {
        // 未分组是种子组，删了无意义（会被重建）且会连带清空组内全部节点。
        // UI 层（设置页、左滑）已有拦截，这里再加一道底线防线，防新增调用方
        // 绕过 UI 直接删掉种子组。
        if (RiftDatabase.groupDao.getById(groupId)?.ungrouped == true) return
        // 2026-11-14，0.2.24 -19，L-28：删组与删组内节点包进同一事务，
        // 进程在两步之间被杀不再留下孤儿节点。
        RiftDatabase.instance.runInTransaction {
            RiftDatabase.groupDao.deleteById(groupId)
            RiftDatabase.proxyDao.deleteByGroup(groupId)
        }
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
        AutoBackup.schedule()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        // 同上：先滤掉未分组种子组，再删；全是未分组时直接返回。
        val deletable = group.filter { !it.ungrouped }
        if (deletable.isEmpty()) return
        // 删组与删组内节点同一事务。
        RiftDatabase.instance.runInTransaction {
            RiftDatabase.groupDao.deleteGroup(deletable)
            RiftDatabase.proxyDao.deleteByGroup(deletable.map { it.id }.toLongArray())
        }
        for (proxyGroup in deletable) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
        AutoBackup.schedule()
    }

}