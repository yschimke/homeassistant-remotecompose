package ee.schimke.ha.rc.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon follows the card design, on the phone and the watch alike. Each icon is an
 * `<activity-alias>` of the app's main activity named `<aliasPackage>.launcher.<key>`; exactly one
 * is enabled at a time. Classic, Home Assistant and Material 3 Expressive share [DEFAULT]; every
 * other design has its own icon, `@mipmap/ic_launcher_<key>`
 * (`scripts/generate-launcher-icons.py`). Most of those are bold marks with a monochrome layer, so
 * themed icons keep the design's silhouette; the most pictorial (LCD, Nixie, Console, LCARS) stay
 * in full colour.
 *
 * Anything that outlives a switch — a widget's PendingIntent, a tile's launch action — must name
 * the main activity itself, never the package's launch intent, which resolves to whichever alias is
 * enabled at the time.
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

  /** The icon key for the card style [styleId] ([HaUberTheme.id]). */
  fun keyFor(styleId: String): String = styleId.takeIf { it in designIcons } ?: DEFAULT

  /**
   * Enables [styleId]'s icon and disables the others. A no-op when it's already the one showing, so
   * the launcher isn't asked to refresh for nothing. The new alias is enabled before the old one is
   * disabled, so the app always has a launcher entry. [aliasPackage] is the package the manifest's
   * relative alias names resolve against (the module namespace).
   */
  fun apply(context: Context, styleId: String, aliasPackage: String) {
    val pm = context.packageManager
    val wanted = keyFor(styleId)
    val keys = listOf(DEFAULT) + designIcons
    fun component(key: String) = ComponentName(context.packageName, "$aliasPackage.launcher.$key")
    fun isEnabled(key: String): Boolean =
      when (pm.getComponentEnabledSetting(component(key))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> key == DEFAULT // the manifest's choice
        else -> false
      }
    fun setEnabled(key: String, enabled: Boolean) {
      if (isEnabled(key) == enabled) return
      pm.setComponentEnabledSetting(
        component(key),
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
      )
    }
    if (keys.all { isEnabled(it) == (it == wanted) }) return
    setEnabled(wanted, true)
    keys.filter { it != wanted }.forEach { setEnabled(it, false) }
  }
}
