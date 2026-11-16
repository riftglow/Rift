package light.rift.core.proxy

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import light.rift.database.DataStore
import light.rift.ktx.Logs
import light.rift.ktx.readableMessage
import java.lang.reflect.Field

object Type {
    const val Text = 0
    const val TextToInt = 1
    const val Int = 2
    const val Bool = 3
}

/** 沿继承链找字段（含私有）：Kotlin 属性的 backing field 是私有的，Class.getField 只找公开字段，-8 Java 转 Kotlin 后永远找不到。 */
internal fun findField(clazz: Class<*>, name: String): Field? {
    var c: Class<*>? = clazz
    while (c != null) {
        try {
            return c.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            c = c.superclass
        }
    }
    return null
}

class PreferenceBinding(
    val type: Int = Type.Text,
    var fieldName: String,
    var bean: Any? = null,
    var pf: PreferenceFragmentCompat? = null
) {

    var cacheName = fieldName
    var disable = false

    fun readStringFromCache(): String {
        return DataStore.profileCacheStore.getString(cacheName) ?: ""
    }

    fun readBoolFromCache(): Boolean {
        return DataStore.profileCacheStore.getBoolean(cacheName, false)
    }

    fun readIntFromCache(): Int {
        return DataStore.profileCacheStore.getInt(cacheName, 0)
    }

    fun readStringToIntFromCache(): Int {
        val value = DataStore.profileCacheStore.getString(cacheName)?.toIntOrNull() ?: 0
        return value
    }

    /** 取 bean 上同名字段；字段不存在就记一行日志，返回 null。 */
    private fun beanField(): Field? {
        val f = findField(bean!!.javaClass, fieldName)
        if (f == null) Logs.d("binding no field: $fieldName")
        return f
    }

    /** 按类型从缓存读当前值；未知类型返回 null，调用方直接跳过。 */
    private fun readCachedValue(): Any? {
        return when (type) {
            Type.Text -> readStringFromCache()
            Type.TextToInt -> readStringToIntFromCache()
            Type.Int -> readIntFromCache()
            Type.Bool -> readBoolFromCache()
            else -> null
        }
    }

    /** 按类型把字段值写回缓存；类型对不上的值直接丢弃。 */
    private fun writeCachedValue(value: Any?) {
        when (type) {
            Type.Text -> {
                if (value is String) {
                    DataStore.profileCacheStore.putString(cacheName, value)
                }
            }
            Type.TextToInt -> {
                if (value is Int) {
                    DataStore.profileCacheStore.putString(cacheName, value.toString())
                }
            }
            Type.Int -> {
                if (value is Int) {
                    DataStore.profileCacheStore.putInt(cacheName, value)
                }
            }
            Type.Bool -> {
                if (value is Boolean) {
                    DataStore.profileCacheStore.putBoolean(cacheName, value)
                }
            }
        }
    }

    fun fromCache() {
        if (disable) return
        val f = beanField() ?: return
        val value = readCachedValue() ?: return
        f.set(bean, value)
    }

    fun writeToCache() {
        if (disable) return
        val f = beanField() ?: return
        writeCachedValue(f.get(bean))
    }

    val preference by lazy {
        pf!!.findPreference<Preference>(cacheName)!!
    }
}
