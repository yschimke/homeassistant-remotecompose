package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.Rc
import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme

/**
 * Concrete fallback palette for Home Assistant cards. Normal in-app rendering uses these colors
 * directly, while remote hosts project them to [RemoteHaTheme].
 */
data class HaTheme(
  val cardBackground: Color,
  val dashboardBackground: Color,
  /**
   * Background of a `type: sections` group container. Cards within the section sit on
   * [cardBackground] above this layer — three-layer stack is `dashboardBackground` →
   * `sectionBackground` → `cardBackground`.
   *
   * Set equal to [dashboardBackground] to opt out of the group surface (a `Surface(color =
   * sectionBackground)` wrap then renders as a no-op). The flat themes — `TerrazzoHome` (HA blue)
   * and `TerrazzoMinimalist` (matt8707) — opt out, since their identity is a single uniform
   * surface; the elevated themes (`Material3`, `TerrazzoMushroom`, `TerrazzoKiosk`) opt in.
   */
  val sectionBackground: Color,
  val primaryText: Color,
  val secondaryText: Color,
  /** Colour for inline markdown links (and linked badge images). */
  val linkText: Color,
  val divider: Color,
  val placeholderAccent: Color,
  val placeholderBackground: Color,
  val unknownAccent: Color,
  val isDark: Boolean,
) {
  companion object {
    // Values sampled from HA's default light theme for
    // `hui-tile-card` (HA 2026-04). Used as the `TerrazzoHome` light
    // palette and as the fallback when no style is resolved.
    val Light =
      HaTheme(
        cardBackground = Color(0xFFFFFFFF),
        dashboardBackground = Color(0xFFFAFAFA),
        sectionBackground = Color(0xFFFAFAFA),
        primaryText = Color(0xFF141414),
        secondaryText = Color(0xFF8F8F8F),
        linkText = Color(0xFF1976D2),
        divider = Color(0xFFE0E0E0),
        placeholderAccent = Color(0xFFB26A00),
        placeholderBackground = Color(0xFFFFF4E5),
        unknownAccent = Color(0xFF757575),
        isDark = false,
      )
    // Values sampled from HA's default dark theme for
    // `hui-tile-card` (HA 2026-04).
    val Dark =
      HaTheme(
        cardBackground = Color(0xFF1C1C1C),
        dashboardBackground = Color(0xFF111111),
        sectionBackground = Color(0xFF111111),
        primaryText = Color(0xFFE1E1E1),
        secondaryText = Color(0xFF878787),
        linkText = Color(0xFF7AB7FF),
        divider = Color(0xFF333333),
        placeholderAccent = Color(0xFFFFB74D),
        placeholderBackground = Color(0xFF2A2016),
        unknownAccent = Color(0xFF8A8F96),
        isDark = true,
      )
  }
}

/**
 * Colors consumed by Remote Compose card components.
 *
 * Unlike [HaTheme], these roles can be runtime values. Wear widgets use the named `WearM3.*` values
 * installed by Remote Material 3, launcher widgets use Android system theme resources, and
 * embedded/preview callers can still project a concrete [HaTheme].
 */
data class RemoteHaTheme(
  val cardBackground: RemoteColor,
  val dashboardBackground: RemoteColor,
  val sectionBackground: RemoteColor,
  val primaryText: RemoteColor,
  val secondaryText: RemoteColor,
  val linkText: RemoteColor,
  val divider: RemoteColor,
  val placeholderAccent: RemoteColor,
  val placeholderBackground: RemoteColor,
  val unknownAccent: RemoteColor,
)

private fun HaTheme.asRemote(): RemoteHaTheme =
  RemoteHaTheme(
    cardBackground = cardBackground.rc,
    dashboardBackground = dashboardBackground.rc,
    sectionBackground = sectionBackground.rc,
    primaryText = primaryText.rc,
    secondaryText = secondaryText.rc,
    linkText = linkText.rc,
    divider = divider.rc,
    placeholderAccent = placeholderAccent.rc,
    placeholderBackground = placeholderBackground.rc,
    unknownAccent = unknownAccent.rc,
  )

/**
 * Map Remote Material 3 roles onto the smaller palette used by HA cards.
 *
 * `tertiary` and `secondary` intentionally carry HA-specific roles that otherwise have no distinct
 * slot in the Wear scheme: link text and unknown-state accent respectively.
 */
fun RemoteColorScheme.asHaTheme(): RemoteHaTheme =
  RemoteHaTheme(
    cardBackground = surfaceContainerHigh,
    dashboardBackground = background,
    sectionBackground = surfaceContainer,
    primaryText = onSurface,
    secondaryText = onSurfaceVariant,
    linkText = tertiary,
    divider = outlineVariant,
    placeholderAccent = primary,
    placeholderBackground = primaryContainer,
    unknownAccent = secondary,
  )

internal fun RemoteHaTheme.asColorScheme(): RemoteColorScheme =
  RemoteColorScheme()
    .copy(
      surfaceContainerHigh = cardBackground,
      background = dashboardBackground,
      surfaceContainer = sectionBackground,
      onSurface = primaryText,
      onSurfaceVariant = secondaryText,
      tertiary = linkText,
      outlineVariant = divider,
      primary = placeholderAccent,
      primaryContainer = placeholderBackground,
      secondary = unknownAccent,
    )

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

/**
 * Derive an [HaTheme] for a given [style] and [darkTheme] flag.
 *
 * The mapping branches by palette identity:
 *
 * - **`Material3`, `TerrazzoMushroom`, `TerrazzoKiosk`** — the "rich" palettes. Map onto Material
 *   3's surface-elevation tokens for a three-layer stack: the page sits on `surface`, sections
 *   group their cards on `surfaceContainer`, and cards themselves sit on `surfaceContainerHigh`.
 *   Picture/unsupported/completed accents come from `primary`/`primaryContainer` so the palette's
 *   hero colour carries through, and dividers use the softer `outlineVariant` decorative role.
 *
 * - **`TerrazzoHome`, `TerrazzoMinimalist`** — the "flat" palettes. Their brief is a single uniform
 *   surface (HA's stock blue dashboard and the matt8707 minimalist look respectively), so they keep
 *   the pre-existing flat mapping: cards on `surface`, dashboard on `background`, divider on
 *   `outline`, accents from `secondary`/`secondaryContainer`. `sectionBackground` is set equal to
 *   `dashboardBackground` so the dashboard's section-group `Surface` wrap renders as a no-op for
 *   these two themes.
 */
fun haThemeFor(style: ThemeStyle, darkTheme: Boolean): HaTheme {
  val m3 = terrazzoColorScheme(style, darkTheme)
  return when (style) {
    ThemeStyle.TerrazzoHome,
    ThemeStyle.TerrazzoMinimalist ->
      HaTheme(
        cardBackground = m3.surface,
        dashboardBackground = m3.background,
        sectionBackground = m3.background,
        primaryText = m3.onSurface,
        secondaryText = m3.onSurfaceVariant,
        linkText = m3.primary,
        divider = m3.outline,
        placeholderAccent = m3.secondary,
        placeholderBackground = m3.secondaryContainer,
        unknownAccent = m3.onSurfaceVariant,
        isDark = darkTheme,
      )
    ThemeStyle.Material3,
    ThemeStyle.TerrazzoMushroom,
    ThemeStyle.TerrazzoKiosk ->
      HaTheme(
        cardBackground = m3.surfaceContainerHigh,
        dashboardBackground = m3.surface,
        sectionBackground = m3.surfaceContainer,
        primaryText = m3.onSurface,
        secondaryText = m3.onSurfaceVariant,
        linkText = m3.primary,
        divider = m3.outlineVariant,
        placeholderAccent = m3.primary,
        placeholderBackground = m3.primaryContainer,
        unknownAccent = m3.onSurfaceVariant,
        isDark = darkTheme,
      )
  }
}

@Composable
fun ProvideHaTheme(theme: HaTheme, content: @Composable () -> Unit) {
  ProvideRemoteHaTheme(theme.asRemote(), content)
}

@Composable
fun ProvideRemoteHaTheme(theme: RemoteHaTheme, content: @Composable () -> Unit) {
  RemoteMaterialTheme(colorScheme = theme.asColorScheme(), content = content)
}

@Composable
fun ProvideSystemHaTheme(content: @Composable () -> Unit) {
  ProvideRemoteHaTheme(SystemRemoteHaTheme, content)
}

@Composable fun currentRemoteHaTheme(): RemoteHaTheme = RemoteMaterialTheme.colorScheme.asHaTheme()

@Composable internal fun haTheme(): RemoteHaTheme = currentRemoteHaTheme()
