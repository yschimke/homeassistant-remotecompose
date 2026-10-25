package ee.schimke.terrazzo

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ee.schimke.terrazzo.core.prefs.CardStylePref

/**
 * The launcher icon follows the card design. Each icon is an `<activity-alias>` of [MainActivity]
 * named `.launcher.<key>` in the manifest; exactly one is enabled at a time. Classic, Home
 * Assistant and Material 3 Expressive share [DEFAULT]; every other design has its own icon
 * (`scripts/generate-launcher-icons.py`). Most of those are bold marks with a monochrome layer, so
 * themed icons keep the design's silhouette; the most pictorial (LCD, Nixie, Console, LCARS) stay
 * in full colour.
 */
object LauncherIcons {
  const val DEFAULT = "default"

  /** Designs with their own icon; the keys match the manifest aliases and `ic_launcher_<key>`. */
  val designIcons: Set<String> =
    setOf(
      "trinitron",
      "lcd",
      "braun",
      "nixie",
      "console",
      "ikea",
      "mushroom",
      "bubble",
      "minimalist",
      "cupertino",
      "catppuccin",
      "obsidian",
      "noctis",
      "lcars",
      "arctic",
      "aeon",
    )

  /** The icon key for [style]. */
  fun keyFor(style: CardStylePref): String = style.id.takeIf { it in designIcons } ?: DEFAULT

  /**
   * Enables [style]'s icon and disables the others. A no-op when it's already the one showing, so
   * the launcher isn't asked to refresh for nothing. The new alias is enabled before the old one is
   * disabled, so the app always has a launcher entry.
   */
  fun apply(context: Context, style: CardStylePref) {
    val pm = context.packageManager
    val wanted = keyFor(style)
    val keys = listOf(DEFAULT) + designIcons
    if (keys.all { isEnabled(pm, context, it) == (it == wanted) }) return
    setEnabled(pm, context, wanted, true)
    keys.filter { it != wanted }.forEach { setEnabled(pm, context, it, false) }
  }

  private fun component(context: Context, key: String) =
    ComponentName(context.packageName, "ee.schimke.terrazzo.launcher.$key")

  private fun isEnabled(pm: PackageManager, context: Context, key: String): Boolean =
    when (pm.getComponentEnabledSetting(component(context, key))) {
      PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
      PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> key == DEFAULT // the manifest's choice
      else -> false
    }

  private fun setEnabled(pm: PackageManager, context: Context, key: String, enabled: Boolean) {
    if (isEnabled(pm, context, key) == enabled) return
    pm.setComponentEnabledSetting(
      component(context, key),
      if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
      else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
      PackageManager.DONT_KILL_APP,
    )
  }
}
