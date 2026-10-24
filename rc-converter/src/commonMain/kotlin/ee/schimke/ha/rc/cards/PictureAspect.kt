package ee.schimke.ha.rc.cards

import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.rc.LocalGridSquareCells
import ee.schimke.ha.rc.components.DEFAULT_PICTURE_ASPECT_RATIO
import ee.schimke.ha.rc.components.parseHaAspectRatio
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Wrap-mode shape (width ÷ height) of a picture-family card: its own `aspect_ratio` when it has
 * a valid one; else square inside a `square: true` grid ([LocalGridSquareCells]); else HA's 16:9.
 */
@Composable
internal fun pictureAspectRatio(card: CardConfig): Float =
  parseHaAspectRatio((card.raw["aspect_ratio"] as? JsonPrimitive)?.content)
    ?: if (LocalGridSquareCells.current) 1f else DEFAULT_PICTURE_ASPECT_RATIO

/**
 * The narrowest grid cell a picture still reads in: room for the image to be recognisable and for
 * the name / state strip to show a few characters each.
 */
internal const val PICTURE_MIN_CELL_WIDTH_DP: Int = 140
