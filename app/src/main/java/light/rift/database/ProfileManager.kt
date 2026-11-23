package light.rift.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import light.rift.R
import light.rift.aidl.TrafficData
import light.rift.fmt.AbstractBean
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ktx.applyDefaultValues
import light.rift.ui.AutoBackup
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
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

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = RiftDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = RiftDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        AutoBackup.schedule()
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        RiftDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
        AutoBackup.schedule()
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        RiftDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
        AutoBackup.schedule()
    }

    // 只写流量（2026-11-23 -66）：TrafficLooper 回写 rx/tx 时用这个，
    // 不走 updateProfile——整实体 @Update 会把配置里的旧 ping 也写回去。
    // 不通知 onUpdated（流量显示走 TrafficData 广播），也不排自动备份
    //（流量 60 秒一刷，排备份是浪费）。
    suspend fun updateTraffic(profile: ProxyEntity) {
        RiftDatabase.proxyDao.updateTraffic(profile.id, profile.rx, profile.tx)
    }

    suspend fun updateTraffic(profiles: List<ProxyEntity>) {
        profiles.forEach {
            RiftDatabase.proxyDao.updateTraffic(it.id, it.rx, it.tx)
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (RiftDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        // 清掉这个节点的动态 key；它是本组主节点时，主节点 key 也清掉。
        DataStore.removeNodeKeys(profileId)
        if (DataStore.groupPrimaryProfile(groupId) == profileId) {
            DataStore.clearGroupPrimaryProfile(groupId)
        }
        AutoBackup.schedule()
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        // 2026-11-14，0.2.24 -19，L-28：删节点与随后的 count/rearrange
        // 包进同一事务，进程在两步之间被杀不再留下半重排的 userOrder。
        // （重排移到监听通知之前，监听方读到的是最终顺序；其余顺序不变。）
        var deleted = false
        RiftDatabase.instance.runInTransaction {
            if (RiftDatabase.proxyDao.deleteById(profileId) != 0) {
                deleted = true
                if (RiftDatabase.proxyDao.countByGroup(groupId) > 1) {
                    GroupManager.rearrange(groupId)
                }
            }
        }
        if (!deleted) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        // 清掉这个节点的动态 key；它是本组主节点时，主节点 key 也清掉。
        DataStore.removeNodeKeys(profileId)
        if (DataStore.groupPrimaryProfile(groupId) == profileId) {
            DataStore.clearGroupPrimaryProfile(groupId)
        }
        iterator { onRemoved(groupId, profileId) }
        AutoBackup.schedule()
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            RiftDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            RiftDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = RiftDatabase.rulesDao.nextOrder() ?: 1
        rule.id = RiftDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        AutoBackup.schedule()
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        RiftDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
        AutoBackup.schedule()
    }

    suspend fun deleteRule(ruleId: Long) {
        RiftDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
        AutoBackup.schedule()
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        RiftDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
        AutoBackup.schedule()
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = RiftDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_quic),
                    port = "443",
                    network = "udp",
                    outbound = -2
                )
            )
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_ads),
                    domains = "geosite:category-ads-all",
                    outbound = -2
                )
            )
            val fuckedCountry = mutableListOf("cn:中国")
            if (Locale.getDefault().country != Locale.CHINA.country) {
                // 非中文用户
                fuckedCountry += "ir:Iran"
                fuckedCountry += "ru:Russia"
            }
            for (c in fuckedCountry) {
                val country = c.substringBefore(":")
                val displayCountry = c.substringAfter(":")
                //
                if (country == "cn") createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_play_store, displayCountry),
                        domains = "googleapis.cn",
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_domain, displayCountry),
                        domains = "geosite:$country",
                        outbound = -1
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_ip, displayCountry),
                        ip = "geoip:$country",
                        outbound = -1
                    ), false
                )
            }
            rules = RiftDatabase.rulesDao.allRules()
        }
        return rules
    }

}
