package ee.schimke.ha.model

import kotlinx.serialization.Serializable

/**
 * One configured Home Assistant install the user has connected to. The
 * client may have several. Each becomes one HA WebSocket session at
 * runtime and one row in the (forthcoming) `ha_servers` Room table.
 *
 * `addonBaseUrl` is optional — if set, the runtime will try the add-on
 * for card rendering and fall back to local rendering on failure. The
 * add-on never serves dashboard structure or live state to the client;
 * those are read from HA Core directly.
 */
@Serializable
data class HaServer(
    /** Stable UUID. Generated once on insert; never reused. */
    val id: String,
    /** User-visible name (e.g. "Home", "Cabin"). */
    val label: String,
    /** HA Core base URL, e.g. `http://homeassistant.local:8123`. */
    val haBaseUrl: String,
    /** HA long-lived access token. Stored encrypted on Android. */
    val accessToken: String,
    /** Optional add-on base URL, e.g. `http://homeassistant.local:8099`. */
    val addonBaseUrl: String? = null,
)
