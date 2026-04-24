package ee.schimke.ha.previews

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import ee.schimke.ha.model.HaSnapshot

/** Fan out toggleable entities over on / off / unavailable states. */
class KitchenLightStatesProvider : PreviewParameterProvider<Pair<String, HaSnapshot>> {
    override val values: Sequence<Pair<String, HaSnapshot>> = sequenceOf(
        "on" to snapshot(state("light.kitchen", "on",
            mapOf("friendly_name" to "Kitchen", "brightness" to "200"))),
        "off" to snapshot(state("light.kitchen", "off",
            mapOf("friendly_name" to "Kitchen"))),
        "unavailable" to snapshot(state("light.kitchen", "unavailable",
            mapOf("friendly_name" to "Kitchen"))),
    )
}

/** Fan out a cover entity over open / closed / opening. */
class GarageCoverStatesProvider : PreviewParameterProvider<Pair<String, HaSnapshot>> {
    override val values: Sequence<Pair<String, HaSnapshot>> = sequenceOf(
        "closed" to snapshot(state("cover.garage", "closed",
            mapOf("friendly_name" to "Garage", "device_class" to "garage"))),
        "open" to snapshot(state("cover.garage", "open",
            mapOf("friendly_name" to "Garage", "device_class" to "garage"))),
        "opening" to snapshot(state("cover.garage", "opening",
            mapOf("friendly_name" to "Garage", "device_class" to "garage"))),
    )
}

/**
 * Animation storyboard for the garage door card: a single entity with
 * synthesised `current_position` values stepping from 0 (fully closed)
 * to 100 (fully open) in 25-point increments. Combined with a
 * `@Preview` fan-out this produces five PNGs that visually
 * reconstruct the open cycle so a reviewer can scan the strip and
 * spot rendering glitches at intermediate frames — clipping at the
 * groove transitions, frame-overflow, motion arrow alignment.
 *
 * The intermediate positions also exercise `state: "opening"` so the
 * motion arrow renders. End frames use `closed` / `open` so the
 * arrow is suppressed at rest.
 */
class GarageDoorPositionsProvider : PreviewParameterProvider<Pair<String, HaSnapshot>> {
    override val values: Sequence<Pair<String, HaSnapshot>> = sequenceOf(
        "closed" to garageSnapshot(state = "closed", positionPct = 0),
        "opening_25" to garageSnapshot(state = "opening", positionPct = 25),
        "opening_50" to garageSnapshot(state = "opening", positionPct = 50),
        "opening_75" to garageSnapshot(state = "opening", positionPct = 75),
        "open" to garageSnapshot(state = "open", positionPct = 100),
    )
}

/**
 * Coarse states for a garage cover that doesn't expose
 * `current_position`. Five-way fan-out exercises every branch of the
 * converter's state-derivation: closed → 100% closed; opening / closing
 * → halfway with the motion arrow; open → fully open; unavailable →
 * defaults to fully closed with an "Unavailable" label.
 */
class GarageDoorStatesProvider : PreviewParameterProvider<Pair<String, HaSnapshot>> {
    override val values: Sequence<Pair<String, HaSnapshot>> = sequenceOf(
        "closed" to garageSnapshot(state = "closed", positionPct = null),
        "opening" to garageSnapshot(state = "opening", positionPct = null),
        "open" to garageSnapshot(state = "open", positionPct = null),
        "closing" to garageSnapshot(state = "closing", positionPct = null),
        "unavailable" to garageSnapshot(state = "unavailable", positionPct = null),
    )
}

private fun garageSnapshot(state: String, positionPct: Int?): HaSnapshot {
    val attrs = buildMap {
        put("friendly_name", "Garage")
        put("device_class", "garage")
        if (positionPct != null) put("current_position", positionPct.toString())
    }
    return snapshot(state("cover.garage", state, attrs))
}

/** Fan out a lock entity over locked / unlocked / locking. */
class FrontDoorLockStatesProvider : PreviewParameterProvider<Pair<String, HaSnapshot>> {
    override val values: Sequence<Pair<String, HaSnapshot>> = sequenceOf(
        "locked" to snapshot(state("lock.front_door", "locked",
            mapOf("friendly_name" to "Front door"))),
        "unlocked" to snapshot(state("lock.front_door", "unlocked",
            mapOf("friendly_name" to "Front door"))),
        "locking" to snapshot(state("lock.front_door", "locking",
            mapOf("friendly_name" to "Front door"))),
    )
}
