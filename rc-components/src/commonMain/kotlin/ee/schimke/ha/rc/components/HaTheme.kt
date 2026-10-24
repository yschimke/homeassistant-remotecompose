@file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteColor
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

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

/**
 * The Remote Material 3 colour role (`WearM3.<role>`) each HA role is carried in, as a named
 * colour: the names a Wear widget host updates and the preview server re-themes a document by.
 */
private object WearM3Roles {
  const val CARD_BACKGROUND = "WearM3.surfaceContainerHigh"
  const val DASHBOARD_BACKGROUND = "WearM3.background"
  const val SECTION_BACKGROUND = "WearM3.surfaceContainer"
  const val PRIMARY_TEXT = "WearM3.onSurface"
  const val SECONDARY_TEXT = "WearM3.onSurfaceVariant"
  const val LINK_TEXT = "WearM3.tertiary"
  const val DIVIDER = "WearM3.outlineVariant"
  const val PLACEHOLDER_ACCENT = "WearM3.primary"
  const val PLACEHOLDER_BACKGROUND = "WearM3.primaryContainer"
  const val UNKNOWN_ACCENT = "WearM3.secondary"
}

/** [this] palette as each role's named `WearM3.<role>` colour, with its value as the default. */
fun HaTheme.asNamedRemote(): RemoteHaTheme =
  RemoteHaTheme(
    cardBackground =
      RemoteColor.createNamedRemoteColor(WearM3Roles.CARD_BACKGROUND, cardBackground),
    dashboardBackground =
      RemoteColor.createNamedRemoteColor(WearM3Roles.DASHBOARD_BACKGROUND, dashboardBackground),
    sectionBackground =
      RemoteColor.createNamedRemoteColor(WearM3Roles.SECTION_BACKGROUND, sectionBackground),
    primaryText = RemoteColor.createNamedRemoteColor(WearM3Roles.PRIMARY_TEXT, primaryText),
    secondaryText = RemoteColor.createNamedRemoteColor(WearM3Roles.SECONDARY_TEXT, secondaryText),
    linkText = RemoteColor.createNamedRemoteColor(WearM3Roles.LINK_TEXT, linkText),
    divider = RemoteColor.createNamedRemoteColor(WearM3Roles.DIVIDER, divider),
    placeholderAccent =
      RemoteColor.createNamedRemoteColor(WearM3Roles.PLACEHOLDER_ACCENT, placeholderAccent),
    placeholderBackground =
      RemoteColor.createNamedRemoteColor(WearM3Roles.PLACEHOLDER_BACKGROUND, placeholderBackground),
    unknownAccent = RemoteColor.createNamedRemoteColor(WearM3Roles.UNKNOWN_ACCENT, unknownAccent),
  )

/**
 * The theme a card draws in when nothing provides one: Remote Material 3's default (dark) scheme,
 * as the named colours it would carry.
 */
private val DefaultRemoteHaTheme: RemoteHaTheme =
  HaTheme(
      cardBackground = Color(0xFF494453),
      dashboardBackground = Color(0xFF000000),
      sectionBackground = Color(0xFF332E3C),
      primaryText = Color(0xFFF6EDFF),
      secondaryText = Color(0xFFCAC4D0),
      linkText = Color(0xFFFFDCC2),
      divider = Color(0xFF615D67),
      placeholderAccent = Color(0xFFE9DDFF),
      placeholderBackground = Color(0xFF4D3D76),
      unknownAccent = Color(0xFFDEE0FF),
      isDark = true,
    )
    .asNamedRemote()

private val LocalRemoteHaTheme = compositionLocalOf { DefaultRemoteHaTheme }

/**
 * [ProvideHaTheme], but with every role written into the document as Remote Material 3's **named**
 * `WearM3.<role>` colour, [theme]'s value as its default, rather than folded into constants.
 *
 * The pixels are the same; what changes is that a player can re-theme the captured document by
 * setting those names, with no recapture. That is how the preview server applies a declared theme
 * to a replayed catalog sticker (`rc.WearM3.<role>=color:…`), and how a host could recolour a card
 * at runtime. Only colours a card takes from the theme follow it: a card style with its own fixed
 * colours (LCD, Nixie, Braun) keeps them.
 */
@Composable
fun ProvideNamedHaTheme(theme: HaTheme, content: @Composable () -> Unit) {
  ProvideRemoteHaTheme(theme.asNamedRemote(), content)
}

@Composable
fun ProvideRemoteHaTheme(theme: RemoteHaTheme, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalRemoteHaTheme provides theme, content = content)
}

@Composable fun currentRemoteHaTheme(): RemoteHaTheme = LocalRemoteHaTheme.current

@Composable internal fun haTheme(): RemoteHaTheme = currentRemoteHaTheme()
