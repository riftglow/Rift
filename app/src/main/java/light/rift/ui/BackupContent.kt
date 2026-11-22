package light.rift.ui

import android.os.Parcel
import android.os.Parcelable
import light.rift.Key
import light.rift.database.ProxyEntity
import light.rift.database.ProxyGroup
import light.rift.database.RiftDatabase
import light.rift.database.SubscriptionBean
import light.rift.database.preference.PublicDatabase
import light.rift.fmt.AbstractBean
import light.rift.fmt.http.HttpBean
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.fmt.shadowsocks.ShadowsocksBean
import light.rift.fmt.socks.SOCKSBean
import light.rift.fmt.ssh.SSHBean
import light.rift.fmt.trojan.TrojanBean
import light.rift.fmt.tuic.TuicBean
import light.rift.fmt.v2ray.StandardV2RayBean
import light.rift.fmt.wireguard.WireGuardBean
import light.rift.ktx.toStringPretty
import light.rift.core.proxy.anytls.AnyTLSBean
import light.rift.core.proxy.config.ConfigBean
import light.rift.core.proxy.shadowtls.ShadowTLSBean
import light.rift.core.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the backup JSON (version 1). Extracted from BackupFragment
 * so the auto-backup worker can build the same content without a UI:
 * manual export/share/upload and auto upload must produce identical
 * files. Sections are all-or-nothing per flag, same as before.
 *
 * Anonymous tier (2026-11-10, plan item 5): export/share only, never
 * auto backup or WebDAV upload. It strips credential values so the
 * file can be shared without leaking access: node passwords/UUIDs/
 * private keys/tokens, subscription links/tokens, and the WebDAV
 * account. Server addresses, ports and protocol options are kept,
 * so the file is still useful for diagnosis. An "anonymous": true
 * marker is written at the top level; import ignores it (an
 * anonymous file restores nodes with blank credentials).
 */
object BackupContent {

    /** Settings keys dropped from an anonymous export. */
    private val SENSITIVE_SETTING_KEYS = setOf(
        Key.WEBDAV_SERVER, Key.WEBDAV_USERNAME, Key.WEBDAV_PASSWORD,
        // 匿名档也必须去掉的密钥与手写配置：Clash API secret（32 位 hex）、
        // 本地代理密码（16 位 hex）、全局自定义配置（用户手写 JSON，理论上
        // 可含敏感内容）。只列 WebDAV 三键会把这三个原样留在匿名档里。
        Key.CLASH_API_SECRET, Key.MIXED_INBOUND_PASS, Key.GLOBAL_CUSTOM_CONFIG
    )

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun build(
        profile: Boolean, rule: Boolean, setting: Boolean,
        anonymous: Boolean = false
    ): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (anonymous) put("anonymous", true)
            if (profile) {
                put("profiles", JSONArray().apply {
                    RiftDatabase.proxyDao.getAll().forEach {
                        put((if (anonymous) sanitizeEntity(it) else it).toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    RiftDatabase.groupDao.allGroups().forEach {
                        put((if (anonymous) sanitizeGroup(it) else it).toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    RiftDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all()
                        .filterNot { anonymous && it.key in SENSITIVE_SETTING_KEYS }
                        .forEach {
                            put(it.toBase64Str())
                        }
                })
            }
        }
        return out.toStringPretty()
    }

    /**
     * Deep-copy the entity, then blank its credential fields. The
     * copy is made with data-class copy() + bean clone(), so the
     * rows in the database are never touched.
     */
    private fun sanitizeEntity(entity: ProxyEntity): ProxyEntity {
        val copy = entity.copy()
        copy.uuid = ""
        copy.error = null
        val bean = copy.requireBean().clone()
        sanitizeBean(bean)
        copy.putBean(bean)
        return copy
    }

    /**
     * Blank credential fields on a bean clone. Non-credential fields
     * (server address, port, protocol options, public keys/certs)
     * are kept for diagnosis. Custom JSON overrides are blanked
     * wholesale: they can carry anything, including credentials.
     * ChainBean holds profile ids only and carries no credentials,
     * so it is kept as-is.
     */
    private fun sanitizeBean(bean: AbstractBean) {
        bean.customOutboundJson = ""
        bean.customConfigJson = ""
        if (bean is StandardV2RayBean) bean.uuid = ""
        when (bean) {
            is TrojanBean -> bean.password = ""
            is ShadowsocksBean -> bean.password = ""
            is SOCKSBean -> {
                bean.username = ""
                bean.password = ""
            }
            is HttpBean -> {
                bean.username = ""
                bean.password = ""
            }
            is HysteriaBean -> {
                bean.authPayload = ""
                bean.obfuscation = ""
            }
            is SSHBean -> {
                bean.username = ""
                bean.password = ""
                bean.privateKey = ""
                bean.privateKeyPassphrase = ""
            }
            is WireGuardBean -> {
                bean.privateKey = ""
                bean.peerPreSharedKey = ""
            }
            is TuicBean -> {
                bean.token = ""
                bean.uuid = ""
                bean.customJSON = ""
            }
            is ShadowTLSBean -> bean.password = ""
            is AnyTLSBean -> bean.password = ""
            is ConfigBean -> bean.config = ""
        }
    }

    /**
     * Copy the group, keeping only the non-sensitive subscription
     * fields. The subscription link/token, username, usage info and
     * traffic counters are dropped; the group name, type, order and
     * the subscription's behaviour flags are kept. A new field added
     * to SubscriptionBean later is dropped by default, which is the
     * safe direction for an anonymous export.
     */
    private fun sanitizeGroup(group: ProxyGroup): ProxyGroup {
        val sub = group.subscription ?: return group
        val clean = SubscriptionBean().apply {
            type = sub.type
            forceResolve = sub.forceResolve
            deduplication = sub.deduplication
            updateWhenConnectedOnly = sub.updateWhenConnectedOnly
            customUserAgent = sub.customUserAgent
            autoUpdate = sub.autoUpdate
            autoUpdateDelay = sub.autoUpdateDelay
            lastUpdated = sub.lastUpdated
            expiryDate = sub.expiryDate
            protocols = sub.protocols
            // Dropped: link, token, username, subscriptionUserinfo,
            // bytesUsed, bytesRemaining.
        }
        return group.copy(subscription = clean)
    }
}
