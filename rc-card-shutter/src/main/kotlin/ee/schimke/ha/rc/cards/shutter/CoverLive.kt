@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.cards.shutter

import androidx.compose.remote.creation.compose.state.RemoteBoolean
import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaLiveBindings
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.toPaddedRemoteString
import ee.schimke.ha.rc.formatState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One cover's live values in the document, and everything the shutter cards draw from them,
 * computed in-document so the card never re-encodes (live-card-updates.md, path 1).
 *
 * The bindings are the host's own: `<id>.state` ([formatState]), `<id>.state_int`
 * ([HaLiveBindings.coverStateInt]), and `<id>.current_position` / `<id>.supported_features`
 * ([HaLiveBindings.attributeFloats], [HaLiveBindings.ABSENT] when the cover reports none). The
 * derivations follow the reference functions in `CoverControls.kt` ([coverControls],
 * [coverStateLabel], [coverClosedFraction], [coverMotion]) exactly; `CoverLiveDocumentTest` holds
 * the two together.
 *
 * Only [assumed] is baked: `assumed_state` is a fixed property of the integration, never of the
 * door's state, and a card design keys it as part of its shape.
 */
internal class CoverLive(
  val state: RemoteString,
  val stateInt: RemoteInt,
  val position: RemoteFloat,
  val features: RemoteFloat,
  private val assumed: Boolean,
  private val unknownFraction: Float,
) {
  private fun stateIs(key: Int): RemoteBoolean = stateInt.isEqualTo(key.ri)

  val closed: RemoteBoolean = stateIs(HaLiveBindings.COVER_CLOSED)
  val open: RemoteBoolean = stateIs(HaLiveBindings.COVER_OPEN)
  val opening: RemoteBoolean = stateIs(HaLiveBindings.COVER_OPENING)
  val closing: RemoteBoolean = stateIs(HaLiveBindings.COVER_CLOSING)
  val moving: RemoteBoolean = opening.or(closing)

  /** A missing cover (`-1`) or an `unavailable` one: every button is greyed. */
  private val usable: RemoteBoolean =
    stateInt.isGreaterThanOrEqualTo(0.ri).and(stateIs(HaLiveBindings.COVER_UNAVAILABLE).not())

  private val hasPosition: RemoteBoolean = position.isGreaterThan(PRESENT_ABOVE.rf)

  /** `current_position` held to 0..100. */
  private val clamped: RemoteFloat = -((-position.min(100f)).min(0f))

  private fun positionOr(ifPosition: RemoteBoolean, otherwise: RemoteBoolean): RemoteBoolean =
    hasPosition.select(ifPosition, otherwise)

  private val fullyOpen: RemoteBoolean = positionOr(clamped.isGreaterThanOrEqualTo(100f.rf), open)
  private val fullyClosed: RemoteBoolean = positionOr(clamped.isLessThan(1f.rf), closed)

  /** `open` part-way: the upstream card's `partial_open`, for `button_*_hide_states`. */
  private val partialOpen: RemoteBoolean =
    open.and(hasPosition).and(fullyOpen.not()).and(fullyClosed.not())

  /** 0 fully open … 1 fully closed: the position when there is one, else one per state. */
  val closedFraction: RemoteFloat =
    hasPosition.select(
      1f.rf - clamped / 100f,
      open.select(
        0f.rf,
        closed.select(1f.rf, moving.select(0.5f.rf, unknownFraction.rf)),
      ),
    )

  /** The state line: `<id>.state`, plus " · 40%" while moving or resting part-way. */
  val label: RemoteString = run {
    val restsAtEnd = closed.and(fullyClosed).or(open.and(fullyOpen))
    val known = usable.and(stateIs(HaLiveBindings.COVER_UNKNOWN).not())
    val withPosition = known.and(hasPosition).and(restsAtEnd.not())
    state + withPosition.select(" · ".rs + clamped.toPaddedRemoteString(1) + "%".rs, "".rs)
  }

  private val supported: RemoteInt =
    features.isLessThan(0f.rf).select(CoverFeature.DEFAULT.toFloat().rf, features).toRemoteInt()

  private fun supports(feature: Int): RemoteBoolean = supported.and(feature.ri).isNotEqualTo(0.ri)

  /** Whether the cover is in one of [states] (`button_*_hide_states`). */
  private fun inAny(states: Set<String>): RemoteBoolean? =
    states
      .mapNotNull { name ->
        when (name) {
          "closed" -> closed
          "open" -> open.and(partialOpen.not())
          "partial_open" -> partialOpen
          "opening" -> opening
          "closing" -> closing
          "unavailable" -> stateIs(HaLiveBindings.COVER_UNAVAILABLE)
          "unknown" -> stateIs(HaLiveBindings.COVER_UNKNOWN)
          else -> null
        }
      }
      .reduceOrNull(RemoteBoolean::or)

  private fun button(feature: Int, hideIn: Set<String>, enabled: RemoteBoolean): CoverButtonLive {
    val hidden = inAny(hideIn)
    val shown = if (hidden == null) supports(feature) else supports(feature).and(hidden.not())
    return CoverButtonLive(shown, if (assumed) usable else usable.and(enabled))
  }

  /** The open, stop and close buttons, as [coverControls] decides them. */
  fun controls(hide: CoverHideStates): CoverControlsLive =
    CoverControlsLive(
      open = button(CoverFeature.OPEN, hide.open, fullyOpen.or(opening).not()),
      stop = button(CoverFeature.STOP, hide.stop, moving),
      close = button(CoverFeature.CLOSE, hide.close, fullyClosed.or(closing).not()),
    )

  companion object {
    /** Below this a position or feature float is [HaLiveBindings.ABSENT]. */
    private const val PRESENT_ABOVE: Float = -1000f

    /**
     * The live values of [entityId], seeded from [entity] so the first frame is current. A null
     * [entityId] (a row with no entity) draws constants: an unavailable cover.
     */
    fun of(entityId: String?, entity: EntityState?, unknownFraction: Float): CoverLive {
      val floats = entity?.let { HaLiveBindings.attributeFloats(it.entityId, it.attributes) }
      fun float(suffix: String) =
        LiveValues.namedFloat(entityId, suffix, floats?.get(suffix) ?: HaLiveBindings.ABSENT)
      return CoverLive(
        state = LiveValues.state(entityId, formatState(entity)),
        stateInt =
          LiveValues.intState(
            entityId,
            entity?.let { HaLiveBindings.coverStateInt(it.state) } ?: -1,
          ),
        position = float(HaLiveBindings.CURRENT_POSITION),
        features = float(HaLiveBindings.SUPPORTED_FEATURES),
        assumed = entity?.let(::isAssumedState) ?: false,
        unknownFraction = unknownFraction,
      )
    }
  }
}

/** Whether [entity] reports `assumed_state: true`: an integration with no position feedback. */
internal fun isAssumedState(entity: EntityState): Boolean =
  (entity.attributes["assumed_state"] as? JsonPrimitive)?.contentOrNull == "true"

/** One cover button in the document: whether it is drawn, and whether it can be tapped. */
internal class CoverButtonLive(val shown: RemoteBoolean, val enabled: RemoteBoolean)

/** [CoverControls], in the document. */
internal class CoverControlsLive(
  val open: CoverButtonLive,
  val stop: CoverButtonLive,
  val close: CoverButtonLive,
)
