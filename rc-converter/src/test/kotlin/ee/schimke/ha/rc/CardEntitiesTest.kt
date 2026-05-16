package ee.schimke.ha.rc

import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardEntitiesTest {

    private fun rawConfig(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun card(json: String, type: String = "tile"): CardConfig =
        CardConfig(type = type, raw = rawConfig(json))

    @Test
    fun singleEntityField() {
        val ids = cardEntityIds(card("""{"entity":"light.kitchen"}"""))
        assertEquals(setOf("light.kitchen"), ids)
    }

    @Test
    fun ignoresEntityFieldWithoutDomain() {
        // `entity: foo` (no `.`) is HA-illegal; skip rather than emit.
        val ids = cardEntityIds(card("""{"entity":"name-not-an-id"}"""))
        assertTrue(ids.isEmpty())
    }

    @Test
    fun entitiesArrayOfStrings() {
        val ids = cardEntityIds(card(
            """{"entities":["sensor.temp","sensor.humidity"]}""",
            type = "history-graph",
        ))
        assertEquals(setOf("sensor.temp", "sensor.humidity"), ids)
    }

    @Test
    fun entitiesArrayOfObjects() {
        val ids = cardEntityIds(card(
            """{"entities":[{"entity":"light.bed","name":"Bed"},
                            {"entity":"switch.fan"}]}""",
            type = "entities",
        ))
        assertEquals(setOf("light.bed", "switch.fan"), ids)
    }

    @Test
    fun nestedConditionalAndStack() {
        // `conditional` wraps `card`, `vertical-stack` wraps `cards`;
        // both should be walked so the inner entity surfaces.
        val ids = cardEntityIds(card(
            """{
              "conditions":[{"entity":"binary_sensor.door","state":"on"}],
              "card":{
                "type":"vertical-stack",
                "cards":[
                  {"type":"tile","entity":"light.kitchen"},
                  {"type":"sensor","entity":"sensor.temp"}
                ]
              }
            }""",
            type = "conditional",
        ))
        assertEquals(
            setOf("binary_sensor.door", "light.kitchen", "sensor.temp"),
            ids,
        )
    }

    @Test
    fun snapshotBindingsForKnownEntities() {
        val snapshot = HaSnapshot(states = mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on"),
            "sensor.temp"   to EntityState("sensor.temp",   "21.4"),
        ))
        val bindings = cardSnapshotBindings(
            setOf("light.kitchen", "sensor.temp"),
            snapshot,
        )
        assertEquals(
            mapOf(
                "light.kitchen.state" to "on",
                "sensor.temp.state"   to "21.4",
            ),
            bindings.strings,
        )
        assertEquals(
            mapOf(
                "light.kitchen.is_on" to true,
                "sensor.temp.is_on"   to false,
            ),
            bindings.booleans,
        )
    }

    @Test
    fun snapshotBindingsSkipMissingEntities() {
        // Card references entities the snapshot doesn't carry yet
        // (e.g. cold-start, or HA hasn't reported them). Skip rather
        // than push an empty string — the document keeps its bake.
        val snapshot = HaSnapshot(states = mapOf(
            "light.kitchen" to EntityState("light.kitchen", "on"),
        ))
        val bindings = cardSnapshotBindings(
            setOf("light.kitchen", "sensor.missing"),
            snapshot,
        )
        assertEquals(setOf("light.kitchen.state"), bindings.strings.keys)
        assertEquals(setOf("light.kitchen.is_on"), bindings.booleans.keys)
    }

    @Test
    fun emptyEntitySetReturnsEmptyBindings() {
        val bindings = cardSnapshotBindings(emptySet(), HaSnapshot())
        assertTrue(bindings.strings.isEmpty())
        assertTrue(bindings.booleans.isEmpty())
    }

    @Test
    fun pictureEntityIdsTopLevel() {
        val ids = cardPictureEntityIds(card(
            """{"type":"picture-entity","entity":"camera.front"}""",
            type = "picture-entity",
        ))
        assertEquals(setOf("camera.front"), ids)
    }

    @Test
    fun pictureEntityIdsSkipsNonPictureCards() {
        // Same `entity` key in a tile card — must not be picked up.
        val ids = cardPictureEntityIds(card(
            """{"type":"tile","entity":"camera.front"}""",
            type = "tile",
        ))
        assertTrue(ids.isEmpty())
    }

    @Test
    fun pictureEntityIdsInsideStack() {
        // A vertical-stack of mixed cards; only the picture-entity
        // children should surface their entities.
        val ids = cardPictureEntityIds(card(
            """{
              "type":"vertical-stack",
              "cards":[
                {"type":"tile","entity":"light.kitchen"},
                {"type":"picture-entity","entity":"camera.front"},
                {"type":"picture-entity","entity":"camera.back"}
              ]
            }""",
            type = "vertical-stack",
        ))
        assertEquals(setOf("camera.front", "camera.back"), ids)
    }

    @Test
    fun pictureEntityIdsSkipsEntitiesWithoutDomain() {
        // `entity:"front"` (no domain.id dot) — HA-illegal, skip.
        val ids = cardPictureEntityIds(card(
            """{"type":"picture-entity","entity":"front"}""",
            type = "picture-entity",
        ))
        assertTrue(ids.isEmpty())
    }
}
