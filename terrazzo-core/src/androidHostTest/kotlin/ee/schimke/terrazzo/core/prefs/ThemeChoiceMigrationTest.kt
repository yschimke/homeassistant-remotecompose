package ee.schimke.terrazzo.core.prefs

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Upgrading from the separate palette and card style settings to the one [ThemeChoice]. */
class ThemeChoiceMigrationTest {
  private val legacyTheme = stringPreferencesKey("theme_style")
  private val legacyCardStyle = stringPreferencesKey("card_style")
  private val design = stringPreferencesKey("theme_design")
  private val variant = stringPreferencesKey("theme_variant")

  @Test
  fun aFreshInstallIsMaterial3() {
    val choice = PreferencesStore.themeChoiceOf(emptyPreferences())
    assertEquals(ThemeChoice.Default, choice)
    assertEquals(ThemePref.Material3, choice.palette)
    assertEquals(CardStylePref.Classic, choice.cardStyle)
  }

  @Test
  fun classicInMaterial3BecomesTheMaterial3Design() {
    assertEquals(
      ThemeChoice(ThemeChoice.MATERIAL3),
      PreferencesStore.themeChoiceOf(
        preferencesOf(legacyTheme to "Material3", legacyCardStyle to "Classic"),
      ),
    )
    // Only a palette stored: the card style was its default, Classic.
    assertEquals(
      ThemeChoice.Default,
      PreferencesStore.themeChoiceOf(preferencesOf(legacyTheme to "Material3")),
    )
  }

  @Test
  fun classicInATerrazzoPaletteKeepsThatPaletteAsItsVariant() {
    for (palette in ThemeChoice.CLASSIC_VARIANTS) {
      val choice =
        PreferencesStore.themeChoiceOf(
          preferencesOf(legacyTheme to palette.name, legacyCardStyle to "Classic"),
        )
      assertEquals(ThemeChoice("classic", palette.name), choice)
      // The same look as before: Classic cards, and that palette on the app and the cards.
      assertEquals(CardStylePref.Classic, choice.cardStyle)
      assertEquals(palette, choice.palette)
    }
    assertEquals(
      ThemeChoice.classic(ThemePref.TerrazzoKiosk),
      PreferencesStore.themeChoiceOf(preferencesOf(legacyTheme to "TerrazzoKiosk")),
    )
  }

  @Test
  fun anyOtherCardStyleBecomesThatDesign() {
    for (style in CardStylePref.entries - CardStylePref.Classic) {
      for (palette in ThemePref.entries) {
        val choice =
          PreferencesStore.themeChoiceOf(
            // The legacy card style was stored by its enum name, not its id.
            preferencesOf(legacyTheme to palette.name, legacyCardStyle to style.name),
          )
        assertEquals(ThemeChoice(style.id), choice)
        assertEquals(style, choice.cardStyle)
        assertEquals(ThemePref.Material3, choice.palette)
      }
    }
  }

  @Test
  fun unknownLegacyValuesFallBackToTheirDefaults() {
    assertEquals(
      ThemeChoice.Default,
      PreferencesStore.themeChoiceOf(
        preferencesOf(legacyTheme to "Future", legacyCardStyle to "Future"),
      ),
    )
    assertEquals(
      ThemeChoice.classic(ThemePref.TerrazzoMushroom),
      PreferencesStore.themeChoiceOf(
        preferencesOf(legacyTheme to "TerrazzoMushroom", legacyCardStyle to "Future"),
      ),
    )
  }

  @Test
  fun aStoredChoiceWinsOverTheLegacySettings() {
    val prefs =
      preferencesOf(
        legacyTheme to "TerrazzoKiosk",
        legacyCardStyle to "Lcd",
        design to "braun",
      )
    assertEquals(ThemeChoice("braun"), PreferencesStore.themeChoiceOf(prefs))
    assertEquals(
      ThemeChoice.classic(ThemePref.TerrazzoMinimalist),
      PreferencesStore.themeChoiceOf(
        preferencesOf(design to "classic", variant to "TerrazzoMinimalist"),
      ),
    )
  }

  @Test
  fun storedChoicesAreNormalized() {
    // Classic without a variant is its default variant; a design without variants drops one.
    assertEquals(
      ThemeChoice.classic(),
      PreferencesStore.themeChoiceOf(preferencesOf(design to "classic")),
    )
    assertEquals(
      ThemeChoice("lcd"),
      PreferencesStore.themeChoiceOf(preferencesOf(design to "lcd", variant to "TerrazzoKiosk")),
    )
    assertEquals(
      ThemeChoice.Default,
      PreferencesStore.themeChoiceOf(preferencesOf(design to "gone")),
    )
  }

  @Test
  fun everyCardStyleIsADesign() {
    for (style in CardStylePref.entries) {
      assertEquals(style, ThemeChoice.of(style).cardStyle)
    }
    assertEquals(ThemePref.TerrazzoHome, ThemeChoice.of(CardStylePref.Classic).palette)
  }
}
