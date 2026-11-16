package light.rift.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import light.rift.R
import light.rift.database.DataStore
import light.rift.ktx.app
import light.rift.utils.PackageCache

/**
 * Preference row that summarises the per-app routing list: up to five app
 * names, one per line, or a "N apps" count when the list is longer.
 */
class AppListPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context, attrs, defStyle,
    )
    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int,
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun getSummary(): CharSequence {
        val labels = DataStore.routePackages
            .split("\n")
            .filter { it.isNotBlank() }
            .map { packageName -> labelFor(packageName) }
        if (labels.isEmpty()) {
            return context.getString(androidx.preference.R.string.not_set)
        }
        if (labels.size <= 5) {
            return labels.joinToString("\n")
        }
        return context.getString(R.string.apps_message, labels.size)
    }

    /** Human-readable label for a package, falling back to the raw name. */
    private fun labelFor(packageName: String): CharSequence {
        val manager = app.packageManager
        val installed = PackageCache.installedPackages[packageName]
            ?.applicationInfo?.loadLabel(manager)
        if (installed != null) return installed
        return packageName
    }

    /** Refresh the summary after the app list changed. */
    fun postUpdate() {
        notifyChanged()
    }

}
