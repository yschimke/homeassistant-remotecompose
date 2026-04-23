package ee.schimke.terrazzo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Typography variants for the four picker options. Each one preserves
 * the Material 3 scale (sizes, weights, line heights) and only swaps
 * the `fontFamily`, so layout stays stable when the user flips options.
 * The Material default is represented by `Typography()` untouched —
 * [typographyFor] returns it directly when the choice is
 * [TypographyChoice.MaterialDefault].
 *
 * Pairings recap (see commit message for the full rationale):
 *
 * - **Expressive**: Roboto Flex display/title, Inter body/label —
 *   sourced from Confetti #1680's Wear stack.
 * - **Atkinson Hyperlegible**: single family across all roles;
 *   maximum glyph differentiation for wall-tablet viewing.
 * - **Lexend**: single family across all roles; research-backed
 *   reading-proficiency gains, wider spacing helps at distance.
 */
internal fun typographyFor(choice: TypographyChoice): Typography = when (choice) {
    TypographyChoice.MaterialDefault -> Typography()
    TypographyChoice.Expressive ->
        Typography().withFamilies(display = RobotoFlexFamily, body = InterFamily)
    TypographyChoice.AtkinsonHyperlegible ->
        Typography().withFamilies(display = AtkinsonHyperlegibleFamily, body = AtkinsonHyperlegibleFamily)
    TypographyChoice.Lexend ->
        Typography().withFamilies(display = LexendFamily, body = LexendFamily)
}

private fun Typography.withFamilies(display: FontFamily, body: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = display),
    displayMedium = displayMedium.copy(fontFamily = display),
    displaySmall = displaySmall.copy(fontFamily = display),
    headlineLarge = headlineLarge.copy(fontFamily = display),
    headlineMedium = headlineMedium.copy(fontFamily = display),
    headlineSmall = headlineSmall.copy(fontFamily = display),
    titleLarge = titleLarge.copy(fontFamily = display),
    titleMedium = titleMedium.copy(fontFamily = display),
    titleSmall = titleSmall.copy(fontFamily = display),
    bodyLarge = bodyLarge.copy(fontFamily = body),
    bodyMedium = bodyMedium.copy(fontFamily = body),
    bodySmall = bodySmall.copy(fontFamily = body),
    labelLarge = labelLarge.copy(fontFamily = body),
    labelMedium = labelMedium.copy(fontFamily = body),
    labelSmall = labelSmall.copy(fontFamily = body),
)
