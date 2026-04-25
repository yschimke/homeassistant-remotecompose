package ee.schimke.ha.model

/**
 * Pixel size + density that a renderer should target when emitting a
 * `.rc` document. Travels with every card request so an add-on or local
 * generator can size text/icons against the surface that will play the
 * document.
 */
data class CardSize(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int = DEFAULT_DENSITY_DPI,
) {
    companion object {
        /** Roughly mdpi×2 — matches xhdpi launchers and is a sane default
         *  when a caller has no real surface to measure against. */
        const val DEFAULT_DENSITY_DPI: Int = 320
    }
}
