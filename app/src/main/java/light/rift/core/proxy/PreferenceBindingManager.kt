package light.rift.core.proxy

import androidx.preference.PreferenceFragmentCompat

/**
 * Owns the [PreferenceBinding]s of one profile-editing screen and fans
 * cache operations out to all of them: bind every row to the same bean,
 * load the bean into the rows, or write the rows back into the bean.
 */
class PreferenceBindingManager {

    /** Bindings registered on this screen, in registration order. */
    val items = mutableListOf<PreferenceBinding>()

    /** Register [binding] and return it, for call-site chaining. */
    fun add(binding: PreferenceBinding): PreferenceBinding {
        items.add(binding)
        return binding
    }

    /** Point every binding at [bean] and load the bean's values into the rows. */
    fun fromCacheAll(bean: Any) {
        items.forEach { binding ->
            binding.bean = bean
            binding.fromCache()
        }
    }

    /** Point every binding at [bean] and write the rows' values into it. */
    fun writeToCacheAll(bean: Any) {
        items.forEach { binding ->
            binding.bean = bean
            binding.writeToCache()
        }
    }

    /** Give every binding the fragment that hosts the preference screen. */
    fun setPreferenceFragment(fragment: PreferenceFragmentCompat) {
        items.forEach { it.pf = fragment }
    }

}
