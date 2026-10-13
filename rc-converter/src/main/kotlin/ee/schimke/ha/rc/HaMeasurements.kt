@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaMeasureKind
import ee.schimke.ha.rc.components.HaPresentation
import ee.schimke.ha.rc.components.HaReading
import ee.schimke.ha.rc.components.HaSpace
import ee.schimke.ha.rc.components.RemoteHaMeasurement
import ee.schimke.ha.rc.components.toRemoteAction
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Layer 1 from Home Assistant state: the [HaReading] of [entityId], as every converter should build
 * one. Its text is `formatState(entity)` and its value the parsed state — exactly what the hosts
 * push to `<id>.state` and `<id>.numeric_state` (live-card-updates.md, path 1) — and its kind comes
 * from the entity's `device_class` and `unit_of_measurement`.
 *
 * [name], [unit], [min] and [max] override the entity's own (a card's `name:`, `unit:`, `min:`,
 * `max:` config); a null range falls back to the kind's typical scale.
 */
fun haReading(
  entityId: String?,
  entity: EntityState?,
  name: String? = null,
  unit: String? = null,
  min: Double? = null,
  max: Double? = null,
  kind: HaMeasureKind? = null,
): HaReading {
  val attr = { key: String -> entity?.attributes?.get(key)?.jsonPrimitive?.contentOrNull }
  val resolvedUnit = unit ?: attr("unit_of_measurement")
  val resolvedKind = kind ?: HaMeasureKind.of(attr("device_class"), resolvedUnit)
  val range = resolvedKind.defaultRange(resolvedUnit)
  val lower = min ?: range.start
  return HaReading.live(
    entityId = entityId,
    name = name ?: attr("friendly_name") ?: entityId ?: "Sensor",
    formatted = formatState(entity),
    numeric = entity?.state?.toFloatOrNull() ?: lower.toFloat(),
    unit = resolvedUnit,
    kind = resolvedKind,
    min = lower,
    max = max ?: range.endInclusive,
  )
}

/** Smallest cell, in dp, that the [HaPresentation.Expanded] measurement looks composed in. */
const val MeasurementExpandedMinWidthDp = 120
const val MeasurementExpandedMinHeightDp = 132

/**
 * A measurement that picks its presentation from the cell it is played in: [HaPresentation.Compact]
 * (visual beside value + name) until the cell is at least [MeasurementExpandedMinWidthDp] ×
 * [MeasurementExpandedMinHeightDp], then [HaPresentation.Expanded] (the visual grows with the cell,
 * the value is promoted and the scale's range appears).
 *
 * One gate ([RemoteMinSizeGate]), so it fires under alpha010's single-breakpoint limit (#224); the
 * continuous part of the adaptation is the visual itself, which is drawn from the canvas size at
 * playback and so grows and shrinks with the cell inside either tier. Both tiers fill or centre in
 * the cell (adaptive-card-layouts.md, Principle 8).
 *
 * For Fixed-mode cells (launcher, Wear, a design-matrix cell). In a Wrap-mode dashboard card use
 * [RemoteHaMeasurement] at [HaPresentation.Standard].
 */
@Composable
fun RemoteHaAdaptiveMeasurement(
  reading: HaReading,
  modifier: RemoteModifier = RemoteModifier,
  tapAction: HaAction = HaAction.None,
) {
  // The tap target sits on the gate, outside both tiers: the same RemoteAction captured in two
  // breakpoint branches blanks the larger one (adaptive-card-layouts.md, Known gaps #9).
  val clickable = tapAction.toRemoteAction()?.let { RemoteModifier.clickable(it) } ?: RemoteModifier
  RemoteMinSizeGate(
    minWidthDp = MeasurementExpandedMinWidthDp,
    minHeightDp = MeasurementExpandedMinHeightDp,
    modifier = modifier.fillMaxSize().then(clickable),
  ) { roomy ->
    if (roomy) {
      RemoteHaMeasurement(reading, HaPresentation.Expanded, RemoteModifier.fillMaxSize())
    } else {
      // Centre the wrap-height compact row with weighted spacers: a wrapper's contentAlignment is a
      // no-op for a fillMaxWidth child in alpha010 (adaptive-card-layouts.md, Known gaps #6).
      RemoteColumn(modifier = RemoteModifier.fillMaxSize().padding(horizontal = HaSpace.M.rdp)) {
        RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
        RemoteHaMeasurement(reading, HaPresentation.Compact, RemoteModifier.fillMaxWidth())
        RemoteBox(modifier = RemoteModifier.fillMaxWidth().weight(1f))
      }
    }
  }
}
