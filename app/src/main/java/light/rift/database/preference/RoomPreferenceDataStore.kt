package light.rift.database.preference

import androidx.preference.PreferenceDataStore

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(private val kvPairDao: KeyValuePair.Dao) :
    PreferenceDataStore() {

    // 不带默认值的读取：键不存在时返回 null，缺省值由调用方决定。
    fun getBoolean(key: String) = read(key)?.boolean
    fun getFloat(key: String) = read(key)?.float
    fun getInt(key: String) = read(key)?.long?.toInt()
    fun getLong(key: String) = read(key)?.long
    fun getString(key: String) = read(key)?.string
    fun getStringSet(key: String) = read(key)?.stringSet

    fun reset() = kvPairDao.reset()

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    // 允许传 null 的写入：null 表示删掉这个键。
    fun putBoolean(key: String, value: Boolean?) =
        putOrRemove(key, value) { k, v -> putBoolean(k, v) }

    fun putFloat(key: String, value: Float?) =
        putOrRemove(key, value) { k, v -> putFloat(k, v) }

    fun putInt(key: String, value: Int?) =
        putOrRemove(key, value) { k, v -> putLong(k, v.toLong()) }

    fun putLong(key: String, value: Long?) =
        putOrRemove(key, value) { k, v -> putLong(k, v) }

    override fun putBoolean(key: String, value: Boolean) =
        write(key, KeyValuePair(key).put(value))

    override fun putFloat(key: String, value: Float) =
        write(key, KeyValuePair(key).put(value))

    override fun putInt(key: String, value: Int) =
        write(key, KeyValuePair(key).put(value.toLong()))

    override fun putLong(key: String, value: Long) =
        write(key, KeyValuePair(key).put(value))

    override fun putString(key: String, value: String?) =
        putOrRemove(key, value) { k, v -> write(k, KeyValuePair(k).put(v)) }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        putOrRemove(key, values) { k, v -> write(k, KeyValuePair(k).put(v)) }

    fun remove(key: String) {
        kvPairDao.delete(key)
        fireChangeListener(key)
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun read(key: String) = kvPairDao[key]

    private fun write(key: String, pair: KeyValuePair) {
        kvPairDao.put(pair)
        fireChangeListener(key)
    }

    private fun <T> putOrRemove(key: String, value: T?, put: (String, T) -> Unit) {
        if (value == null) remove(key) else put(key, value)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()

    private fun fireChangeListener(key: String) {
        val snapshot = synchronized(listeners) {
            listeners.toList()
        }
        snapshot.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }
}
