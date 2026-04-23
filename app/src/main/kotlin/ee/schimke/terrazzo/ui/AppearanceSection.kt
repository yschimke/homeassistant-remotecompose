package ee.schimke.terrazzo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.terrazzo.ui.theme.AtkinsonHyperlegibleFamily
import ee.schimke.terrazzo.ui.theme.ColorSource
import ee.schimke.terrazzo.ui.theme.DarkMode
import ee.schimke.terrazzo.ui.theme.InterFamily
import ee.schimke.terrazzo.ui.theme.LexendFamily
import ee.schimke.terrazzo.ui.theme.ThemeSettings
import ee.schimke.terrazzo.ui.theme.TypographyChoice

/**
 * Three segmented pickers (colour source, typography, dark mode) that
 * mutate a single [ThemeSettings]. The MVP picker from the Wear design
 * system port — options will be pruned once real HA dashboards tell us
 * which ones are keepers.
 */
@Composable
fun AppearanceSection(
    settings: ThemeSettings,
    onColorSource: (ColorSource) -> Unit,
    onTypography: (TypographyChoice) -> Unit,
    onDarkMode: (DarkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)

        PickerRow(
            label = "Colours",
            options = ColorSource.entries,
            selected = settings.colorSource,
            onSelected = onColorSource,
            labelFor = ::colorSourceLabel,
        )

        PickerRow(
            label = "Typography",
            options = TypographyChoice.entries,
            selected = settings.typography,
            onSelected = onTypography,
            labelFor = ::typographyLabel,
            fontFor = ::typographyFontFamily,
        )

        PickerRow(
            label = "Dark mode",
            options = DarkMode.entries,
            selected = settings.darkMode,
            onSelected = onDarkMode,
            labelFor = ::darkModeLabel,
        )
    }
}

@Composable
private fun <T> PickerRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    labelFor: (T) -> String,
    fontFor: ((T) -> FontFamily?)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.padding(horizontal = 2.dp),
                ) {
                    Text(
                        text = labelFor(option),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = fontFor?.invoke(option),
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private fun colorSourceLabel(source: ColorSource): String = when (source) {
    ColorSource.MaterialDynamic -> "Material You"
    ColorSource.Custom -> "HA blue"
}

private fun typographyLabel(choice: TypographyChoice): String = when (choice) {
    TypographyChoice.MaterialDefault -> "System"
    TypographyChoice.Expressive -> "Expressive"
    TypographyChoice.AtkinsonHyperlegible -> "Atkinson"
    TypographyChoice.Lexend -> "Lexend"
}

/**
 * Preview each typography button in its own family so the user sees
 * the distinction before tapping. Google Fonts downloads are cached
 * after first use, so the first render of a not-yet-fetched face shows
 * a system fallback — a known trade-off of the downloadable approach.
 */
private fun typographyFontFamily(choice: TypographyChoice): FontFamily? = when (choice) {
    TypographyChoice.MaterialDefault -> null
    TypographyChoice.Expressive -> InterFamily
    TypographyChoice.AtkinsonHyperlegible -> AtkinsonHyperlegibleFamily
    TypographyChoice.Lexend -> LexendFamily
}

private fun darkModeLabel(mode: DarkMode): String = when (mode) {
    DarkMode.FollowSystem -> "Auto"
    DarkMode.Light -> "Light"
    DarkMode.Dark -> "Dark"
}
