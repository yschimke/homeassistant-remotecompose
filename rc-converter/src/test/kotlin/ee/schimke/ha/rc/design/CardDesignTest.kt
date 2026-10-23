package ee.schimke.ha.rc.design

import kotlin.test.Test
import kotlin.test.assertEquals

class CardDesignTest {
  private fun design(roots: String, nodes: String) =
    CardDesign.parse(
      """{"id":"t","catalogPin":{"systemId":"ha-cards"},"roots":$roots,"nodes":$nodes}""",
      CardDesigns.components,
    )

  @Test
  fun theCheckedInDesignsValidate() {
    for (cardType in listOf("tile", "button", "entity", "sensor")) {
      val design = requireNotNull(CardDesigns.design(cardType)) { "no $cardType.uid packaged" }
      assertEquals(emptyList(), design.validate(), cardType)
    }
  }

  @Test
  fun aRootThatIsNotANodeIsRejected() {
    assertEquals(
      listOf("root missing is not a node"),
      design(
          """["missing"]""",
          """{"tile":{"componentId":"ha-cards/tile-full"}}""",
        )
        .validate(),
    )
  }
}
