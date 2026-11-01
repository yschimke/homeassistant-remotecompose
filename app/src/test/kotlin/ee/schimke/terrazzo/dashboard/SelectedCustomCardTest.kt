package ee.schimke.terrazzo.dashboard

import androidx.test.core.app.ApplicationProvider
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.ha.client.design.DesignedBlob
import ee.schimke.ha.client.design.DesignsById
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.SystemHaClock
import ee.schimke.ha.rc.cards.defaultRegistry
import ee.schimke.ha.rc.cards.shutter.withEnhancedShutter
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.ha.rc.components.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectedCustomCardTest {
  @Test
  fun phoneUsesSelectedAnchorAndOriginalCustomCardBindings() {
    val raw =
      Json.parseToJsonElement(
          """{"type":"custom:enhanced-shutter-card","entities":[{"entity":"cover.garage","top_offset_pct":13}]}"""
        )
        .jsonObject
    val card = CardConfig("custom:enhanced-shutter-card", raw)
    val snapshot =
      HaSnapshot(states = mapOf("cover.garage" to EntityState("cover.garage", "closed")))
    val style = ThemeStyle.Material3
    val key = assertNotNull(CardDesignTemplates.selectedDesignKeyFor(card, snapshot, style, false))
    val bytes = RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0)), emptyList()))
    val designs =
      CardDesigns(
        byDesign = DesignsById(mapOf("user:door" to listOf(DesignedBlob(key, null, bytes))))
      )
    val playback =
      assertNotNull(
        cardTemplateFor(
          ApplicationProvider.getApplicationContext(),
          card,
          snapshot,
          designs,
          style,
          false,
          defaultRegistry().withEnhancedShutter(),
          HaUberTheme.Classic,
          recordUsage = false,
          designId = "user:door",
          selectedCard = card,
        )
      )
    assertTrue("cover.s0.is_on" in playback.bindings(snapshot, SystemHaClock).booleans)
  }
}
