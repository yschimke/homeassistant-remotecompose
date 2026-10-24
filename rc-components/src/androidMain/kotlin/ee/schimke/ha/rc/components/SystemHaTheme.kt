@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.ha.rc.components

import androidx.compose.remote.core.Operations
import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.profile.Profile
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * A colour the **host** resolves from Android's current system theme, written into the document as
 * a `ColorTheme` operation carrying a light and a dark resource plus fallbacks.
 *
 * Built through [RemoteColor]'s id-provider constructor, and that is the whole point: it must
 * **not** carry a constant value. `BackgroundModifier` branches on `hasConstantValue` — a colour
 * that has one takes `SolidBackgroundModifier` and is written as literal red/green/blue, and the id
 * provider is never invoked. Subclassing `RemoteColor(lightFallback)` and overriding
 * `writeToDocument` therefore produced documents with **no** `ColorTheme` operation at all: every
 * launcher colour reached the bytes as its light fallback, which renders plausibly (the fallback is
 * what a host without the resources draws anyway) and silently loses the theming.
 *
 * The cache key is the colour's identity, so two roles resolving the same pair of resources share
 * one document entry while different roles stay distinct.
 *
 * Built through [remoteDocumentColor] — see its KDoc for why a themed colour must not carry a
 * constant value, and what silently happens when it does.
 */
private fun systemThemeColor(
  role: String,
  lightResource: Short,
  darkResource: Short,
  lightFallback: Color,
  darkFallback: Color,
): RemoteColor {
  val lightArgb = lightFallback.toArgb()
  val darkArgb = darkFallback.toArgb()
  return remoteDocumentColor(
    "SystemTheme.$role:$lightResource/$darkResource:$lightArgb/$darkArgb"
  ) { creationState ->
    creationState.document
      .addThemedColor(Rc.AndroidColors.GROUP, lightResource, darkResource, lightArgb, darkArgb)
      .toInt()
  }
}

/**
 * Launcher palette resolved by the RemoteViews host from Android's current system theme.
 *
 * Each role carries light/dark resource ids plus concrete fallbacks, so one cold-start document
 * follows both wallpaper colors and night mode without a recapture.
 */
val SystemRemoteHaTheme: RemoteHaTheme =
  RemoteHaTheme(
    cardBackground =
      systemThemeColor(
        "cardBackground",
        Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_LIGHT,
        Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_HIGH_DARK,
        HaTheme.Light.cardBackground,
        HaTheme.Dark.cardBackground,
      ),
    dashboardBackground =
      systemThemeColor(
        "dashboardBackground",
        Rc.AndroidColors.SYSTEM_BACKGROUND_LIGHT,
        Rc.AndroidColors.SYSTEM_BACKGROUND_DARK,
        HaTheme.Light.dashboardBackground,
        HaTheme.Dark.dashboardBackground,
      ),
    sectionBackground =
      systemThemeColor(
        "sectionBackground",
        Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_LIGHT,
        Rc.AndroidColors.SYSTEM_SURFACE_CONTAINER_DARK,
        HaTheme.Light.sectionBackground,
        HaTheme.Dark.sectionBackground,
      ),
    primaryText =
      systemThemeColor(
        "primaryText",
        Rc.AndroidColors.SYSTEM_ON_SURFACE_LIGHT,
        Rc.AndroidColors.SYSTEM_ON_SURFACE_DARK,
        HaTheme.Light.primaryText,
        HaTheme.Dark.primaryText,
      ),
    secondaryText =
      systemThemeColor(
        "secondaryText",
        Rc.AndroidColors.SYSTEM_ON_SURFACE_VARIANT_LIGHT,
        Rc.AndroidColors.SYSTEM_ON_SURFACE_VARIANT_DARK,
        HaTheme.Light.secondaryText,
        HaTheme.Dark.secondaryText,
      ),
    linkText =
      systemThemeColor(
        "linkText",
        Rc.AndroidColors.SYSTEM_PRIMARY_LIGHT,
        Rc.AndroidColors.SYSTEM_PRIMARY_DARK,
        HaTheme.Light.linkText,
        HaTheme.Dark.linkText,
      ),
    divider =
      systemThemeColor(
        "divider",
        Rc.AndroidColors.SYSTEM_OUTLINE_VARIANT_LIGHT,
        Rc.AndroidColors.SYSTEM_OUTLINE_VARIANT_DARK,
        HaTheme.Light.divider,
        HaTheme.Dark.divider,
      ),
    placeholderAccent =
      systemThemeColor(
        "placeholderAccent",
        Rc.AndroidColors.SYSTEM_PRIMARY_LIGHT,
        Rc.AndroidColors.SYSTEM_PRIMARY_DARK,
        HaTheme.Light.placeholderAccent,
        HaTheme.Dark.placeholderAccent,
      ),
    placeholderBackground =
      systemThemeColor(
        "placeholderBackground",
        Rc.AndroidColors.SYSTEM_PRIMARY_CONTAINER_LIGHT,
        Rc.AndroidColors.SYSTEM_PRIMARY_CONTAINER_DARK,
        HaTheme.Light.placeholderBackground,
        HaTheme.Dark.placeholderBackground,
      ),
    unknownAccent =
      systemThemeColor(
        "unknownAccent",
        Rc.AndroidColors.SYSTEM_ON_SURFACE_VARIANT_LIGHT,
        Rc.AndroidColors.SYSTEM_ON_SURFACE_VARIANT_DARK,
        HaTheme.Light.unknownAccent,
        HaTheme.Dark.unknownAccent,
      ),
  )

@Composable
fun ProvideSystemHaTheme(
  profile: Profile,
  fallbackTheme: HaTheme,
  content: @Composable () -> Unit,
) {
  if (Operations.COLOR_THEME in profile.supportedOperations) {
    ProvideRemoteHaTheme(SystemRemoteHaTheme, content)
  } else {
    ProvideHaTheme(fallbackTheme, content)
  }
}
