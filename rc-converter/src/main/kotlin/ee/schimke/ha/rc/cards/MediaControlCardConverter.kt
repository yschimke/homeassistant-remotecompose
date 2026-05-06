package ee.schimke.ha.rc.cards

import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.CardTypes
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaMediaControlData
import ee.schimke.ha.rc.components.RemoteHaMediaControl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `media-control` card. Reads `media_title`, `media_artist`, position +
 * duration from the entity. Transport buttons fire
 * `media_player.media_*`; Play/Pause auto-toggles based on the current
 * play state.
 */
class MediaControlCardConverter : CardConverter {
    override val cardType: String = CardTypes.MEDIA_CONTROL

    override fun naturalHeightDp(card: CardConfig, snapshot: HaSnapshot): Int = 168

    @Composable
    override fun Render(card: CardConfig, snapshot: HaSnapshot, modifier: RemoteModifier) {
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

        fun svc(svcName: String): HaAction = entityId?.let { id ->
            HaAction.CallService(
                domain = "media_player",
                service = svcName,
                entityId = id,
                serviceData = JsonObject(emptyMap()),
            )
        } ?: HaAction.None

        RemoteHaMediaControl(
            HaMediaControlData(
                playerName = playerName.rs,
                title = title.rs,
                artist = artist?.rs,
                accent = accent,
                isPlaying = isPlaying,
                positionFraction = fraction,
                positionLabel = if (dur > 0f) formatHms(pos.toLong()).rs else null,
                durationLabel = if (dur > 0f) formatHms(dur.toLong()).rs else null,
                previousAction = svc("media_previous_track"),
                playPauseAction = svc(if (isPlaying) "media_pause" else "media_play"),
                nextAction = svc("media_next_track"),
            ),
            modifier = modifier,
        )
    }
}

private fun formatHms(totalSeconds: Long): String {
    if (totalSeconds < 0) return "0:00"
    val h = totalSeconds / 3600
    val m = (totalSeconds / 60) % 60
    val s = totalSeconds % 60
    return if (h > 0) "$h:%02d:%02d".format(m, s) else "%d:%02d".format(m, s)
}
