package light.rift.database.preference

import androidx.preference.PreferenceDataStore

/**
 * Notified whenever a value inside a [PreferenceDataStore] changes, so
 * screens that mirror the store (for example the profile editor) can mark
 * themselves dirty.
 */
interface OnPreferenceDataStoreChangeListener {

    /** Called after [key] in [store] was written. */
    fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String)

}
