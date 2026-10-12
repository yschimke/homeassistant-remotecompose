package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.design.DesignProperties
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardSizeMode
import ee.schimke.ha.rc.CardSnapshotBindings
import ee.schimke.ha.rc.DesignTemplateInput
import ee.schimke.ha.rc.LocalCardSizeMode
import ee.schimke.ha.rc.RemoteSizeBreakpoint
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaMediaControlData
import ee.schimke.ha.rc.components.RemoteHaMediaControl
import ee.schimke.ha.rc.components.RemoteHaMediaControlWide
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `media-control` card. Reads `media_title`, `media_artist`, position + duration from the entity.
 * Transport buttons fire `media_player.media_*`; Play/Pause auto-toggles based on the current play
 * state.
 */
class MediaControlCardConverter : CardConverter {
  override val cardType: String = CardTypes.MEDIA_CONTROL

  // Title / artist / artwork / progress are baked from attributes, not bound
  // (see CardConverter.dataSignature), so re-encode when the track changes.
  // media_position ticks every second and is excluded on purpose — the progress
  // bar is baked at capture and re-encoding each frame would be wasteful.
  override fun dataSignature(card: CardConfig, snapshot: HaSnapshot): String {
    val id = card.raw["entity"]?.jsonPrimitive?.content ?: return ""
    val s = snapshot.states[id] ?: return "$id="
    val a = s.attributes
    return buildString {
      append(id).append('=').append(s.state)
      append('|').append(a["media_title"])
      append('|').append(a["media_artist"])
      append('|').append(a["media_duration"])
      append('|').append(a["entity_picture"])
    }
  }

  override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 168

  /**
   * A media control renders from a card design. Whether it is playing (play or pause), whether it
   * has an artist and a known position are its structure; titles, the player name, the progress
   * (`<slot>.media_position_fraction`) and the time labels are bindings. A transport tap is matched
   * against the template's own buttons and rebuilt for the live player.
   */
  override fun designProperties(card: CardConfig, snapshot: HaSnapshot): DesignProperties? {
    if (!setOf("type", "entity").containsAll(card.raw.keys)) return null
    val entityId = card.raw["entity"]?.jsonPrimitive?.content ?: return null
    if (CardDesignSlots.domainOf(entityId) != "media_player" || entityId !in snapshot.states)
      return null
    val data = mediaData(card, snapshot)
    return DesignProperties.build {
      enum("playing", if (data.isPlaying) 1 else 0)
      enum("has_artist", if (data.artist != null) 1 else 0)
      enum("has_position", if (data.positionLabel != null) 1 else 0)
    }
  }

  override fun designTemplate(properties: DesignProperties): DesignTemplateInput? {
    val playing = properties["playing"] ?: return null
    val slot = CardDesignSlots.slotId("media_player", 0)
    val attributes = buildMap {
      put("media_title", "Title")
      if (properties["has_artist"] == "1") put("media_artist", "Artist")
      if (properties["has_position"] == "1") {
        put("media_position", "30")
        put("media_duration", "60")
      }
    }
    val entity =
      CardDesignSlots.templateEntity(
        slot,
        placeholderState = if (playing == "1") "playing" else "paused",
        attributes = attributes,
      )
    val card =
      CardConfig(
        type = cardType,
        raw =
          kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive(cardType))
            put("entity", kotlinx.serialization.json.JsonPrimitive(slot))
          },
      )
    return DesignTemplateInput(card, HaSnapshot(states = mapOf(slot to entity)))
  }

  override fun designBindings(card: CardConfig, snapshot: HaSnapshot): CardSnapshotBindings {
    val entityId =
      card.raw["entity"]?.jsonPrimitive?.content
        ?: return CardSnapshotBindings(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    val slot = CardDesignSlots.slotId(CardDesignSlots.domainOf(entityId), 0)
    val data = mediaData(card, snapshot)
    return CardSnapshotBindings(
      buildMap {
        put("$slot.name", data.playerName)
        put("$slot.attributes.media_title", data.title)
        data.artist?.let { put("$slot.attributes.media_artist", it) }
        data.positionLabel?.let { put("$slot.attributes.media_position_label", it) }
        data.durationLabel?.let { put("$slot.attributes.media_duration_label", it) }
      },
      emptyMap(),
      emptyMap(),
      mapOf("$slot.media_position_fraction" to data.positionFraction),
    )
  }

  override fun designAction(
    card: CardConfig,
    snapshot: HaSnapshot?,
    slot: Int,
    templateAction: HaAction,
  ): HaAction? {
    snapshot ?: return null
    val input = designTemplate(designProperties(card, snapshot) ?: return null) ?: return null
    val placeholder = mediaData(input.card, input.snapshot)
    val live = mediaData(card, snapshot)
    return when (templateAction) {
      placeholder.previousAction -> live.previousAction
      placeholder.playPauseAction -> live.playPauseAction
      placeholder.nextAction -> live.nextAction
      else -> null
    }
  }

  @Composable
  override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
    val data = mediaData(card, snapshot)

    // Single-gate Wide↔Full ladder, mirroring the arc-dial family
    // (see RenderArcDial / #355): the art + play/pause hero is kept
    // at every size; narrow cells drop the transport row + seek bar
    // to the Wide chip, wide cells get the full card.
    when (LocalCardSizeMode.current) {
      CardSizeMode.Wrap -> RemoteHaMediaControl(data, modifier)
      CardSizeMode.Fixed ->
        RemoteSizeBreakpoint(
          thresholdsDp = intArrayOf(MediaFullMinDp),
          modifier = modifier.fillMaxSize(),
        ) { tier ->
          when (tier) {
            0 -> RemoteHaMediaControlWide(data, RemoteModifier.fillMaxSize())
            else -> RemoteHaMediaControl(data, RemoteModifier.fillMaxSize(), fillHeight = true)
          }
        }
    }
  }
}

private const val MediaFullMinDp = 260

private fun formatHms(totalSeconds: Long): String {
  if (totalSeconds < 0) return "0:00"
  val h = totalSeconds / 3600
  val m = (totalSeconds / 60) % 60
  val s = totalSeconds % 60
  return if (h > 0) "$h:%02d:%02d".format(m, s) else "%d:%02d".format(m, s)
}

/** The player's data for [card] and [snapshot]: what it draws, and what a template binds. */
private fun mediaData(card: CardConfig, snapshot: HaSnapshot): HaMediaControlData {
  val entityId = card.raw["entity"]?.jsonPrimitive?.content
  val entity = entityId?.let { snapshot.states[it] }
  val attrs = entity?.attributes ?: JsonObject(emptyMap())
  val playerName = attrs["friendly_name"]?.jsonPrimitive?.content ?: entityId ?: "Player"
  val title = attrs["media_title"]?.jsonPrimitive?.content ?: "Idle"
  val artist = attrs["media_artist"]?.jsonPrimitive?.content
  val state = entity?.state ?: "off"
  val isPlaying = state == "playing"
  val pos = attrs["media_position"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
  val dur = attrs["media_duration"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
  val fraction = if (dur > 0f) (pos / dur).coerceIn(0f, 1f) else 0f
  val accent = Color(0xFF673AB7)

  fun svc(svcName: String): HaAction =
    entityId?.let { id ->
      HaAction.CallService(
        domain = "media_player",
        service = svcName,
        entityId = id,
        serviceData = JsonObject(emptyMap()),
      )
    } ?: HaAction.None

  return HaMediaControlData(
    entityId = entityId,
    playerName = playerName,
    title = title,
    artist = artist,
    accent = accent,
    isPlaying = isPlaying,
    positionFraction = fraction,
    positionLabel = if (dur > 0f) formatHms(pos.toLong()) else null,
    durationLabel = if (dur > 0f) formatHms(dur.toLong()) else null,
    previousAction = svc("media_previous_track"),
    playPauseAction = svc(if (isPlaying) "media_pause" else "media_play"),
    nextAction = svc("media_next_track"),
  )
}
