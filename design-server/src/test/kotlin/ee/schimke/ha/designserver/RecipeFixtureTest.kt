package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.design.CardBindingRecipe
import ee.schimke.ha.design.CardBindingRecipes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * `gauge-unit.rc` is a gauge template captured by rc-converter (`CardBindingRecipeTemplateTest`),
 * which the Home Assistant card's browser test plays. It must carry the current gauge recipe, so
 * that test exercises what a freshly captured template would; recapture it after changing
 * [CardBindingRecipes.GAUGE].
 */
class RecipeFixtureTest {
  @Test
  fun gaugeFixtureCarriesTheCurrentRecipe() {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/gauge-unit.rc")).use { it.readBytes() }
    val operations = RcDocumentCodec.decode(bytes, RcWireLimits()).operations
    val variable =
      operations.filterIsInstance<RcNamedVariable>().single {
        it.name.removePrefix("USER:") == CardBindingRecipe.BINDING
      }
    val text = operations.filterIsInstance<RcTextData>().single { it.id == variable.id }.text
    assertEquals(CardBindingRecipes.GAUGE.toJson(), Json.parseToJsonElement(text))
  }
}
