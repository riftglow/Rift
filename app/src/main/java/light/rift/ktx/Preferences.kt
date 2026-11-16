package light.rift.ktx

import androidx.preference.PreferenceDataStore
import kotlin.reflect.KProperty

/**
 * Delegated-property bridges over a [PreferenceDataStore]: each factory
 * builds a [PreferenceProxy] that reads/writes one typed key, so settings
 * can be declared as `var foo by store.string("foo")`.
 */

fun PreferenceDataStore.string(
    name: String,
    defaultValue: () -> String = { "" },
) = PreferenceProxy(name, defaultValue, ::getString, ::putString)

fun PreferenceDataStore.boolean(
    name: String,
    defaultValue: () -> Boolean = { false },
) = PreferenceProxy(name, defaultValue, ::getBoolean, ::putBoolean)

fun PreferenceDataStore.int(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, ::getInt, ::putInt)

fun PreferenceDataStore.stringSet(
    name: String,
    defaultValue: () -> Set<String> = { setOf() },
) = PreferenceProxy(name, defaultValue, ::getStringSet, ::putStringSet)

fun PreferenceDataStore.stringToInt(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, { key, fallback ->
    getString(key, "$fallback")?.toIntOrNull() ?: fallback
}, { key, value ->
    putString(key, "$value")
})

fun PreferenceDataStore.stringToIntIfExists(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, { key, fallback ->
    getString(key, "$fallback")?.toIntOrNull() ?: fallback
}, { key, value ->
    // Non-positive values mean "not set": store an empty string instead.
    putString(key, value.takeIf { it > 0 }?.toString() ?: "")
})

fun PreferenceDataStore.long(
    name: String,
    defaultValue: () -> Long = { 0L },
) = PreferenceProxy(name, defaultValue, ::getLong, ::putLong)

fun PreferenceDataStore.stringToLong(
    name: String,
    defaultValue: () -> Long = { 0L },
) = PreferenceProxy(name, defaultValue, { key, fallback ->
    getString(key, "$fallback")?.toLongOrNull() ?: fallback
}, { key, value ->
    putString(key, "$value")
})

/**
 * A single typed key inside a [PreferenceDataStore], usable as a delegated
 * property. Reading a missing key yields [defaultValue]; writing stores it.
 */
class PreferenceProxy<T>(
    val name: String,
    val defaultValue: () -> T,
    val getter: (String, T) -> T?,
    val setter: (String, value: T) -> Unit,
) {

    operator fun getValue(thisObj: Any?, property: KProperty<*>): T =
        getter(name, defaultValue())!!

    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) {
        setter(name, value)
    }

}
