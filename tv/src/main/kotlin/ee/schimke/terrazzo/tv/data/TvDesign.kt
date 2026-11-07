package ee.schimke.terrazzo.tv.data

import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle

/**
 * One design on the TV's short list: the card style the kiosk is drawn in, and the palette its
 * chrome (and Classic cards) take. The TV's one theme choice, as the design list is the phone's.
 */
data class TvDesign(
  val id: String,
  val title: String,
  val subtitle: String?,
  val palette: ThemeStyle,
  val cardStyle: HaUberTheme,
) {
  companion object {
    /** Classic in the high-contrast Kiosk palette: wall-mounted dashboards are the TV's brief. */
    val Kiosk =
      TvDesign(
        id = HaUberTheme.Classic.id,
        title = "Kiosk",
        subtitle = "The cards' own look · high-contrast teal, Atkinson Hyperlegible",
        palette = ThemeStyle.TerrazzoKiosk,
        cardStyle = HaUberTheme.Classic,
      )

    /** Classic in Material 3's colours. */
    val Material3 =
      TvDesign(
        id = "material3",
        title = "Material 3",
        subtitle = "The cards' own look · Material 3 colours",
        palette = ThemeStyle.Material3,
        cardStyle = HaUberTheme.Classic,
      )

    /** The designs the TV offers, [Kiosk] (the default) first. */
    val all: List<TvDesign> =
      listOf(Kiosk, Material3) +
        listOf(
            HaUberTheme.HomeAssistant,
            HaUberTheme.Expressive,
            HaUberTheme.Console,
            HaUberTheme.Lcd,
            HaUberTheme.Trinitron,
          )
          .map(::styled)

    /**
     * The design with [id]: one of [all], else any other card style by its id (one an earlier
     * build let the TV pick), else [Kiosk].
     */
    fun byId(id: String?): TvDesign =
      all.firstOrNull { it.id == id }
        ?: HaUberTheme.all.firstOrNull { it.id == id }?.let(::styled)
        ?: Kiosk

    /**
     * The design an earlier build's two settings made: its palette ([ThemeStyle] by name) and card
     * style (an `HaUberTheme` id). Classic in Material 3 is [Material3]; Classic in any Terrazzo
     * palette becomes [Kiosk]; any other card style is that design.
     */
    fun fromLegacy(themeStyle: String?, cardStyle: String?): TvDesign =
      when {
        cardStyle != null && cardStyle != HaUberTheme.Classic.id -> byId(cardStyle)
        themeStyle == ThemeStyle.Material3.name -> Material3
        else -> Kiosk
      }

    private fun styled(theme: HaUberTheme) =
      TvDesign(
        id = theme.id,
        title = theme.displayName,
        subtitle = null,
        palette = ThemeStyle.TerrazzoKiosk,
        cardStyle = theme,
      )
  }
}
