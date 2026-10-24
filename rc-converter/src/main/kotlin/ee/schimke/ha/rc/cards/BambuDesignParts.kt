@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.design.CardDesignComponent
import ee.schimke.ha.rc.design.LocalCardInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parts of the Bambu Lab cards (card-designs/ui-builder/designs/`custom--ha-bambulab-*.uid`):
 * each draws the card being drawn ([LocalCardInput]) as its converter does, from the printer it
 * resolves. The card design renderer draws them through [components]; ha-cards-catalog's wrappers
 * put the same calls on UI Builder's shelf.
 */
object BambuDesignParts {
  private val ams = BambuLabAmsCardConverter()
  private val spool = BambuLabSpoolCardConverter()
  private val printStatus = BambuLabPrintStatusCardConverter()
  private val printControl = BambuLabPrintControlCardConverter()
  private val skipObject = BambuLabSkipObjectCardConverter()

  /** The card being drawn, or [converter]'s card with no printer in UI Builder's canvas. */
  @Composable
  private fun Draw(converter: BambuLabCardConverter, modifier: RemoteModifier) {
    val (card, snapshot) =
      LocalCardInput.current
        ?: DesignTemplateInput(
          CardConfig(
            converter.cardType,
            JsonObject(mapOf("type" to JsonPrimitive(converter.cardType))),
          ),
          HaSnapshot(),
        )
    converter.Draw(card, snapshot, modifier)
  }

  /** `ha-cards/bambulab-ams-card`: the AMS's spool grid. */
  @Composable fun Ams(modifier: RemoteModifier) = Draw(ams, modifier)

  /** `ha-cards/bambulab-spool-card`: one spool's detail. */
  @Composable fun Spool(modifier: RemoteModifier) = Draw(spool, modifier)

  /** `ha-cards/bambulab-print-status-card`: the current print job and its progress. */
  @Composable fun PrintStatus(modifier: RemoteModifier) = Draw(printStatus, modifier)

  /** `ha-cards/bambulab-print-control-card`: the pause, resume and stop pad. */
  @Composable fun PrintControl(modifier: RemoteModifier) = Draw(printControl, modifier)

  /** `ha-cards/bambulab-skipobject-card`: the print control pad, by its legacy card type. */
  @Composable fun SkipObject(modifier: RemoteModifier) = Draw(skipObject, modifier)

  internal val components: Map<String, CardDesignComponent> =
    mapOf(
      "ha-cards/bambulab-ams-card" to { _, modifier -> Ams(modifier) },
      "ha-cards/bambulab-spool-card" to { _, modifier -> Spool(modifier) },
      "ha-cards/bambulab-print-status-card" to { _, modifier -> PrintStatus(modifier) },
      "ha-cards/bambulab-print-control-card" to { _, modifier -> PrintControl(modifier) },
      "ha-cards/bambulab-skipobject-card" to { _, modifier -> SkipObject(modifier) },
    )
}
