package ee.schimke.ha.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Resolved Lovelace dashboard config, matching the payload returned by the
 * HA WebSocket command `lovelace/config`.
 *
 * The schema is intentionally loose: HA treats any unknown field on a card as
 * opaque, and the frontend's TypeScript types carry an `[key: string]: any`
 * escape hatch. We surface `extra: JsonObject` on every config node so
 * converters can read card-specific fields without us having to model them
 * up front.
 */
@Serializable
data class Dashboard(
    val title: String? = null,
    val views: List<View> = emptyList(),
)

@Serializable
data class View(
    val title: String? = null,
    val path: String? = null,
    val icon: String? = null,
    val theme: String? = null,
    val type: String? = null,
    val sections: List<Section> = emptyList(),
    val cards: List<CardConfig> = emptyList(),
    val badges: List<JsonObject> = emptyList(),
)

@Serializable
data class Section(
    val type: String? = null,
    val title: String? = null,
    val cards: List<CardConfig> = emptyList(),
)

/**
 * A Lovelace card config. Only `type` is guaranteed — everything else lives
 * in [raw] for per-card converters to interpret.
 */
@Serializable
data class CardConfig(
    val type: String,
    val raw: JsonObject = JsonObject(emptyMap()),
)
