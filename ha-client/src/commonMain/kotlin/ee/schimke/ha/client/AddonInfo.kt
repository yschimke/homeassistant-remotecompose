package ee.schimke.ha.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The RemoteCompose add-on's answer to `GET /v1/info`: which of its endpoints this build serves, in
 * one request. The same shape and rules as the integration's `terrazzo/info` ([TerrazzoInfo]):
 * [protocol] changes only when a change breaks clients, [minClient] is the oldest client protocol
 * still served, and a capability that is missing is not served.
 *
 * An add-on older than this endpoint answers it 404; [AddonClient.info] answers null then, and a
 * client falls back to trying each endpoint.
 */
@Serializable
data class AddonInfo(
  val protocol: Int,
  @SerialName("min_client") val minClient: Int = 1,
  val version: String? = null,
  val capabilities: AddonCapabilities = AddonCapabilities(),
) {
  companion object {
    /** The protocol this client speaks, and the one the add-on in this repository serves. */
    const val PROTOCOL = 1
    const val MIN_CLIENT = 1

    /**
     * How the add-on writes the answer: every field, so `min_client` is there to read, but no
     * capability it leaves out.
     */
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}

/**
 * What the add-on serves. Each is a revision; null means not served by this build. Capabilities
 * this client doesn't know are ignored.
 */
@Serializable
data class AddonCapabilities(
  /** `/v1/dashboards` and `/v1/dashboards/{path}`. */
  val dashboards: Int? = null,
  /** `/v1/snapshot`: the add-on's whole state cache. */
  val snapshot: Int? = null,
  /** `/v1/stream`: state changes over a WebSocket. */
  val stream: Int? = null,
  /** `/v1/cards/{key}.rc`: cards encoded on the server. */
  val cards: Int? = null,
)
