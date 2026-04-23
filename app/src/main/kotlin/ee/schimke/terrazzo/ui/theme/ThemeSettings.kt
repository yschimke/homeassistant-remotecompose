package ee.schimke.terrazzo.ui.theme

/**
 * Appearance preferences the user can flip from Settings. Kept small on
 * purpose — this is the MVP picker, we'll prune options once real HA
 * dashboards tell us which combinations are worth keeping.
 *
 * Shape borrowed from Confetti's Wear design system
 * (joreilly/confetti#1680) but recast for a phone + wall-tablet HA app:
 *
 * - [colorSource] separates "Material You / dynamic" from a fixed
 *   seed-derived palette, so pre-Android-12 devices and wall displays
 *   that want HA-brand colour both get a proper scheme.
 * - [typography] exposes three custom stacks tuned for glance-from-
 *   across-the-room legibility alongside the Material default.
 * - [darkMode] is explicit (dark / light / follow system) rather than
 *   piggy-backing on [isSystemInDarkTheme] alone — wall mounts often
 *   need the opposite of phone defaults.
 */
data class ThemeSettings(
    val colorSource: ColorSource = ColorSource.MaterialDynamic,
    val typography: TypographyChoice = TypographyChoice.MaterialDefault,
    val darkMode: DarkMode = DarkMode.FollowSystem,
)

enum class ColorSource {
    /** `dynamicDark/LightColorScheme` on Android 12+, Material baseline below. */
    MaterialDynamic,

    /** Fixed HA-blue seed routed through `materialkolor` — works on every SDK. */
    Custom,
}

enum class TypographyChoice {
    /** Compose `Typography()` — system Roboto, no downloadable font. */
    MaterialDefault,

    /** Confetti's Expressive stack: Roboto Flex display/title + Inter body/label. */
    Expressive,

    /** Braille Institute's max-differentiation face; single family across roles. */
    AtkinsonHyperlegible,

    /** Shaver-Troup reading-proficiency family; single family across roles. */
    Lexend,
}

enum class DarkMode { FollowSystem, Light, Dark }
