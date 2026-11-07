package ee.schimke.terrazzo.core.prefs

/**
 * The app's one theme choice: a [design] from the design list and, for a design that has them, one
 * of its [variant]s. Everything else that used to be chosen separately follows from it: how cards
 * are drawn ([cardStyle]) and the palette the app and Classic cards are drawn in ([palette]).
 *
 * The designs are [MATERIAL3] (the cards' own look in the system's dynamic, wallpaper-seeded
 * colours) and every [CardStylePref] by its id. Only [CardStylePref.Classic] has variants today:
 * the curated Terrazzo palettes ([CLASSIC_VARIANTS]), stored by [ThemePref] name. Every other
 * design brings its own palette, and the app around a design that doesn't theme the whole app
 * keeps Material 3's dynamic colours.
 */
data class ThemeChoice(val design: String, val variant: String? = null) {

  /** How dashboard cards are drawn under this choice. */
  val cardStyle: CardStylePref
    get() =
      if (design == MATERIAL3) CardStylePref.Classic
      else CardStylePref.entries.firstOrNull { it.id == design } ?: CardStylePref.Classic

  /** The palette the app's screens (and Classic cards) are drawn in under this choice. */
  val palette: ThemePref
    get() = if (design == CardStylePref.Classic.id) classicVariant(variant) else ThemePref.Material3

  /**
   * This choice with an unknown design replaced by [Default] and a variant only where the design
   * has one, so two stored spellings of the same look compare equal.
   */
  fun normalized(): ThemeChoice =
    when {
      design == MATERIAL3 -> ThemeChoice(MATERIAL3)
      design == CardStylePref.Classic.id -> ThemeChoice(design, classicVariant(variant).name)
      CardStylePref.entries.any { it.id == design } -> ThemeChoice(design)
      else -> Default
    }

  companion object {
    /** The Material 3 design: Classic cards on the system's dynamic colours. */
    const val MATERIAL3: String = "material3"

    /** A fresh install's choice. */
    val Default: ThemeChoice = ThemeChoice(MATERIAL3)

    /** Classic's variants, its first the default: the curated Terrazzo palettes. */
    val CLASSIC_VARIANTS: List<ThemePref> =
      listOf(
        ThemePref.TerrazzoHome,
        ThemePref.TerrazzoMushroom,
        ThemePref.TerrazzoMinimalist,
        ThemePref.TerrazzoKiosk,
      )

    /** Classic in [palette], its default variant when [palette] isn't one of [CLASSIC_VARIANTS]. */
    fun classic(palette: ThemePref = CLASSIC_VARIANTS.first()): ThemeChoice =
      ThemeChoice(CardStylePref.Classic.id, classicVariant(palette.name).name)

    /** The design that draws cards in [style], its default variant where it has some. */
    fun of(style: CardStylePref): ThemeChoice =
      if (style == CardStylePref.Classic) classic() else ThemeChoice(style.id)

    /**
     * The choice an install from before the design list made with its two settings: a palette
     * ([ThemePref] by name) and a card style ([CardStylePref] by name), either absent for its
     * default. Classic in Material 3 is the Material 3 design; Classic in a Terrazzo palette is
     * Classic with that palette as its variant; any other card style is that design (its palette
     * only ever coloured the app's own screens around it, which now keep Material 3's).
     */
    fun fromLegacy(themeStyle: String?, cardStyle: String?): ThemeChoice {
      val style =
        cardStyle?.let { name -> CardStylePref.entries.firstOrNull { it.name == name } }
          ?: CardStylePref.Classic
      if (style != CardStylePref.Classic) return ThemeChoice(style.id)
      val palette =
        themeStyle?.let { name -> ThemePref.entries.firstOrNull { it.name == name } }
          ?: ThemePref.Material3
      return if (palette == ThemePref.Material3) Default else classic(palette)
    }

    private fun classicVariant(variant: String?): ThemePref =
      CLASSIC_VARIANTS.firstOrNull { it.name == variant } ?: CLASSIC_VARIANTS.first()
  }
}
