package ee.schimke.ha.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.composeai.preview.WearThemeCatalog
import ee.schimke.ha.rc.components.HaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.ha.rc.components.asWearColorScheme
import ee.schimke.ha.rc.components.haThemeFor

/**
 * The palette [HaCardSticker] captures in. Home Assistant's light theme unless one of the declared
 * themes below wraps the render.
 */
val LocalHaCatalogTheme = staticCompositionLocalOf { HaTheme.Light }

/**
 * The `ha-cards` catalog's declared themes: Home Assistant's own light and dark themes, and the
 * app's palettes (`ThemeStyle`) in each mode. They are what the served catalog's Theme control
 * offers.
 *
 * `@WearThemeCatalog`, not `@ThemeCatalog`, because the stickers draw through Remote Material 3:
 * the token set the publish resolves for each theme is the Wear `MaterialTheme` it installs, whose
 * roles are the `WearM3.<role>` names [HaCardSticker]'s documents carry. So a published sticker is
 * re-themed by setting those names, with no recapture. Only colours a card takes from the theme
 * change; a card style with its own fixed colours (LCD, Nixie, Braun) keeps them.
 */
@Composable
private fun HaThemeOverride(theme: HaTheme, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = theme.asWearColorScheme()) {
    CompositionLocalProvider(LocalHaCatalogTheme provides theme, content = content)
  }
}

@WearThemeCatalog(name = "Home Assistant Light", group = "Home Assistant")
class HaLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = HaThemeOverride(HaTheme.Light, content)
}

@WearThemeCatalog(name = "Home Assistant Dark", group = "Home Assistant")
class HaDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) = HaThemeOverride(HaTheme.Dark, content)
}

@WearThemeCatalog(name = "Material 3 Light", group = "Terrazzo")
class Material3LightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.Material3, darkTheme = false), content)
}

@WearThemeCatalog(name = "Material 3 Dark", group = "Terrazzo")
class Material3DarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.Material3, darkTheme = true), content)
}

@WearThemeCatalog(name = "Home Light", group = "Terrazzo")
class TerrazzoHomeLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoHome, darkTheme = false), content)
}

@WearThemeCatalog(name = "Home Dark", group = "Terrazzo")
class TerrazzoHomeDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoHome, darkTheme = true), content)
}

@WearThemeCatalog(name = "Mushroom Light", group = "Terrazzo")
class TerrazzoMushroomLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoMushroom, darkTheme = false), content)
}

@WearThemeCatalog(name = "Mushroom Dark", group = "Terrazzo")
class TerrazzoMushroomDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoMushroom, darkTheme = true), content)
}

@WearThemeCatalog(name = "Minimalist Light", group = "Terrazzo")
class TerrazzoMinimalistLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoMinimalist, darkTheme = false), content)
}

@WearThemeCatalog(name = "Minimalist Dark", group = "Terrazzo")
class TerrazzoMinimalistDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoMinimalist, darkTheme = true), content)
}

@WearThemeCatalog(name = "Kiosk Light", group = "Terrazzo")
class TerrazzoKioskLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoKiosk, darkTheme = false), content)
}

@WearThemeCatalog(name = "Kiosk Dark", group = "Terrazzo")
class TerrazzoKioskDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    HaThemeOverride(haThemeFor(ThemeStyle.TerrazzoKiosk, darkTheme = true), content)
}
