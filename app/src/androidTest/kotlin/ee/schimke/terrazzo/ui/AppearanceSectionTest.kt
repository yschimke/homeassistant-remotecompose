package ee.schimke.terrazzo.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ee.schimke.terrazzo.ui.theme.DarkMode
import ee.schimke.terrazzo.ui.theme.ThemeSettings
import ee.schimke.terrazzo.ui.theme.TypographyChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Smoke test for the settings appearance pickers. Renders
 * [AppearanceSection] against a `createComposeRule()` host (no
 * Activity — avoids the LAN-discovery flow in [MainActivity] which
 * can't reach anything on a CI emulator) and checks the three
 * picker groups are visible + callbacks fire on tap.
 *
 * Deliberately minimal. Its first job is to prove the instrumented-
 * test plumbing (AndroidJUnitRunner, Compose UI testing, emulator
 * bring-up in CI) works end-to-end. Richer coverage — real theme
 * settings persisted via DataStore, navigation through the nav
 * suite — can layer on once the plumbing is stable.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {

    @get:Rule val compose = createComposeRule()

    @Test fun rendersAllThreePickerGroups() {
        compose.setContent {
            AppearanceSection(
                settings = ThemeSettings(),
                onColorSource = {},
                onTypography = {},
                onDarkMode = {},
            )
        }

        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Colours").assertIsDisplayed()
        compose.onNodeWithText("Typography").assertIsDisplayed()
        compose.onNodeWithText("Dark mode").assertIsDisplayed()
        // Picker options surface their labels directly.
        compose.onNodeWithText("Material You").assertIsDisplayed()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        compose.onNodeWithText("System").assertIsDisplayed()
    }

    @Test fun darkModeClickPropagatesToCallback() {
        var picked: DarkMode? = null
        compose.setContent {
            AppearanceSection(
                settings = ThemeSettings(),
                onColorSource = {},
                onTypography = {},
                onDarkMode = { picked = it },
            )
        }

        compose.onNodeWithText("Dark").performClick()

        assertEquals(DarkMode.Dark, picked)
    }

    @Test fun typographyClickPropagatesToCallback() {
        var picked: TypographyChoice? = null
        compose.setContent {
            AppearanceSection(
                settings = ThemeSettings(),
                onColorSource = {},
                onTypography = { picked = it },
                onDarkMode = {},
            )
        }

        compose.onNodeWithText("Lexend").performClick()

        assertEquals(TypographyChoice.Lexend, picked)
    }
}
