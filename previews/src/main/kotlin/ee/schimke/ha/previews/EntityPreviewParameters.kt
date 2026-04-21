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
