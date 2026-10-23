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
  fun theCheckedInTileDesignValidates() {
    assertEquals(emptyList(), requireNotNull(CardDesigns.design("tile")).validate())
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
