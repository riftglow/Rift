package light.rift.ktx

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import light.rift.R

/**
 * Open [link] in a Chrome Custom Tab tinted with Rift's primary color.
 * Does nothing when no browser on the device can handle the intent.
 */
fun Context.launchCustomTab(link: String) {
    val toolbarColor = getColorAttr(R.attr.colorPrimary)
    val tabIntent = CustomTabsIntent.Builder()
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
        .setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_LIGHT,
            colorSchemeParams(toolbarColor),
        )
        .setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_DARK,
            colorSchemeParams(toolbarColor),
        )
        .build()
    if (tabIntent.intent.resolveActivity(packageManager) == null) return
    tabIntent.launchUrl(this, Uri.parse(link))
}

private fun colorSchemeParams(toolbarColor: Int): CustomTabColorSchemeParams =
    CustomTabColorSchemeParams.Builder()
        .setToolbarColor(toolbarColor)
        .build()
