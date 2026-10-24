package ee.schimke.ha.rc.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a tile's features fill the rows below its name: see [tileFeatureRows]. */
class TileFeatureRowsTest {
  private val stepper =
    HaTileFeature.NumberButtons("22.0 °C", HaAction.None, HaAction.None, "Target")

  private fun modes(label: String) =
    HaTileFeature.ModeSelect(
      listOf(HaTileModeOption(label, null, selected = true, action = HaAction.None)),
      showIcons = false,
    )

  private val heat = modes("Heat")
  private val fan = modes("Fan")
  private val preset = modes("Eco")

  @Test
  fun atTheBottomEveryFeatureHasItsOwnRow() {
    assertEquals(
      listOf(listOf(stepper), listOf(heat)),
      tileFeatureRows(listOf(stepper, heat), HaTileFeaturePosition.Bottom),
    )
  }

  @Test
  fun inlineTheRestShareRowsTwoAtATime() {
    // The first is beside the name.
    assertEquals(
      listOf(listOf(fan, preset)),
      tileFeatureRows(listOf(heat, fan, preset), HaTileFeaturePosition.Inline),
    )
    assertEquals(emptyList(), tileFeatureRows(listOf(heat), HaTileFeaturePosition.Inline))
  }

  @Test
  fun inlineAStepperKeepsARowToItself() {
    assertEquals(
      listOf(listOf(stepper), listOf(fan, preset)),
      tileFeatureRows(listOf(heat, fan, stepper, preset), HaTileFeaturePosition.Inline),
    )
    assertEquals(
      listOf(listOf(stepper), listOf(fan)),
      tileFeatureRows(listOf(heat, stepper, fan), HaTileFeaturePosition.Inline),
    )
  }
}
