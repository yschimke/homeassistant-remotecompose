package ee.schimke.terrazzo.dashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An agent's draft comes into the editor only when it was made from what the editor saves over. */
class AdjustDraftTest {
  private fun config(title: String) =
    Json.parseToJsonElement("""{"views":[{"title":"$title"}]}""") as JsonObject

  @Test
  fun sameBaselineIsAdjustable() {
    assertTrue(canAdjust(config("Home"), config("Home")))
  }

  @Test
  fun changedDashboardIsRefused() {
    assertFalse(canAdjust(config("Home"), config("Home, renamed")))
  }

  @Test
  fun unknownBaselineIsRefused() {
    assertFalse(canAdjust(null, config("Home")))
  }
}
