package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.runtime.Composable
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.model.toTyped
import ee.schimke.ha.rc.BreakpointAxis
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.HaStateColor
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaPictureEntityData
import ee.schimke.ha.rc.components.HaToggleAccent
import ee.schimke.ha.rc.components.LiveValues
import ee.schimke.ha.rc.components.RemoteHaPictureEntity
import ee.schimke.ha.rc.defaultTapActionFor
import ee.schimke.ha.rc.formatState
import ee.schimke.ha.rc.icons.HaIconMap
import ee.schimke.ha.rc.parseHaAction
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `picture-entity` card. RemoteCompose alpha08 can't pull a live camera stream into a `.rc`
 * document, so the renderer paints a tinted placeholder area + name/state bottom bar; tap forwards
 * to the entity's default action (more-info / toggle).
 */
class PictureEntityCardConverter : CardConverter {
  override val cardType: String = CardTypes.PICTURE_ENTITY

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 160

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val entityId = card.raw["entity"]?.jsonPrimitive?.content
    val entity = entityId?.let { snapshot.states[it] }
    val name =
      card.raw["name"]?.jsonPrimitive?.content
        ?: entity?.attributes?.get("friendly_name")?.jsonPrimitive?.content
        ?: entityId
        ?: "(no entity)"
    val showName = card.raw["show_name"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
    val showState = card.raw["show_state"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
    val tapCfg = card.raw["tap_action"]?.jsonObject
    val tapAction =
      if (tapCfg != null) parseHaAction(tapCfg, entityId) else defaultTapActionFor(entityId)

    val isActive = entity?.toTyped()?.isActive
    val imageUrl = entity?.attributes?.get("entity_picture")?.jsonPrimitive?.content
    val frameStamp = entity?.attributes?.get("demo_frame_stamp")?.jsonPrimitive?.content
    val data =
      HaPictureEntityData(
        entityId = entityId,
        name = name,
        state = LiveValues.state(entityId, formatState(entity)),
        icon = HaIconMap.resolve(card.raw["icon"]?.jsonPrimitive?.content, entity),
        accent =
          HaToggleAccent(
            activeAccent = HaStateColor.activeFor(entity).rc,
            inactiveAccent = HaStateColor.inactiveFor(entity).rc,
            initiallyOn = isActive ?: false,
            toggleable = isActive != null,
          ),
        showName = showName,
        showState = showState,
        imageUrl = imageUrl,
        frameStamp = frameStamp,
        tapAction = tapAction,
      )

    when (LocalCardSizeMode.current) {
      // Dashboard: the HA reference band (fixed natural height).
      CardSizeMode.Wrap -> RemoteHaPictureEntity(data, modifier = modifier)
      // Launcher / Wear: the image is the identity edge-to-edge —
      // it fills and crops the whole cell at every size (no
      // letterbox). A single width gate drops the name at the
      // smallest cell so the state badge alone reads, and keeps the
      // full name+state scrim on wider cells. Single threshold —
      // alpha010 collapses nested ladders to tier 0 (#224).
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(PICTURE_IDENTITY_THRESHOLD_DP),
          modifier = modifier,
          axis = BreakpointAxis.Width,
        ) { tier ->
          val tierData = if (tier == 0) data.copy(showName = false) else data
          RemoteHaPictureEntity(
            tierData,
            modifier = RemoteModifier.fillMaxSize(),
            fillHeight = true,
          )
        }
    }
  }
}

/**
 * Width gate (dp) for the picture-entity identity tier. Below it (a `2×N` launcher cell) the name
 * strip drops so only the state badge sits over the image; at or above it the full name+state scrim
 * returns. Same ~72 dp cell maths as the bulk family ([BULK_IDENTITY_THRESHOLD_DP]).
 */
private const val PICTURE_IDENTITY_THRESHOLD_DP: Int = 180
