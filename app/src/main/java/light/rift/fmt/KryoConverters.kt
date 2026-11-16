package light.rift.fmt

import androidx.room.TypeConverter
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import light.rift.core.proxy.anytls.AnyTLSBean
import light.rift.core.proxy.config.ConfigBean
import light.rift.core.proxy.shadowtls.ShadowTLSBean
import light.rift.core.utils.JavaUtil
import light.rift.database.SubscriptionBean
import light.rift.fmt.http.HttpBean
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.fmt.internal.ChainBean
import light.rift.fmt.shadowsocks.ShadowsocksBean
import light.rift.fmt.socks.SOCKSBean
import light.rift.fmt.ssh.SSHBean
import light.rift.fmt.trojan.TrojanBean
import light.rift.fmt.tuic.TuicBean
import light.rift.fmt.v2ray.VMessBean
import light.rift.fmt.wireguard.WireGuardBean
import light.rift.ktx.Logs
import light.rift.ktx.byteBuffer

object KryoConverters {

    private val NULL = ByteArray(0)

    @TypeConverter
    @JvmStatic
    fun serialize(bean: Serializable?): ByteArray {
        if (bean == null) return NULL
        val out = ByteArrayOutputStream()
        val buffer = out.byteBuffer()
        bean.serializeToBuffer(buffer)
        buffer.flush()
        buffer.close()
        return out.toByteArray()
    }

    @JvmStatic
    fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T {
        if (bytes == null) return bean
        val input = ByteArrayInputStream(bytes)
        val buffer = input.byteBuffer()
        try {
            bean.deserializeFromBuffer(buffer)
        } catch (e: KryoException) {
            Logs.w(e)
        }
        bean.initializeDefaultValues()
        return bean
    }

    @TypeConverter
    @JvmStatic
    fun socksDeserialize(bytes: ByteArray?): SOCKSBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(SOCKSBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun httpDeserialize(bytes: ByteArray?): HttpBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(HttpBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun shadowsocksDeserialize(bytes: ByteArray?): ShadowsocksBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(ShadowsocksBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun configDeserialize(bytes: ByteArray?): ConfigBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(ConfigBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun vmessDeserialize(bytes: ByteArray?): VMessBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(VMessBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun trojanDeserialize(bytes: ByteArray?): TrojanBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(TrojanBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun hysteriaDeserialize(bytes: ByteArray?): HysteriaBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(HysteriaBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun sshDeserialize(bytes: ByteArray?): SSHBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(SSHBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun wireguardDeserialize(bytes: ByteArray?): WireGuardBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(WireGuardBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun tuicDeserialize(bytes: ByteArray?): TuicBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(TuicBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun shadowTLSDeserialize(bytes: ByteArray?): ShadowTLSBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(ShadowTLSBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun anyTLSDeserialize(bytes: ByteArray?): AnyTLSBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(AnyTLSBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun chainDeserialize(bytes: ByteArray?): ChainBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(ChainBean(), bytes)
    }

    @TypeConverter
    @JvmStatic
    fun subscriptionDeserialize(bytes: ByteArray?): SubscriptionBean? {
        if (JavaUtil.isEmpty(bytes)) return null
        return deserialize(SubscriptionBean(), bytes)
    }
}
