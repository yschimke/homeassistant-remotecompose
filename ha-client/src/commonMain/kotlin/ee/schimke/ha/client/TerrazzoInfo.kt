package ee.schimke.ha.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Terrazzo integration's answer to `terrazzo/info`: which of its commands this Home Assistant
 * has, for the signed-in user (`custom_components/terrazzo/capabilities.py`).
 *
 * [protocol] changes only when a change breaks clients; [minClient] is the oldest client protocol
 * the integration still serves. [version] is the integration's release (its manifest's).
 */
@Serializable
data class TerrazzoInfo(
  val protocol: Int,
  @SerialName("min_client") val minClient: Int = 1,
  val version: String? = null,
  val capabilities: TerrazzoCapabilities = TerrazzoCapabilities(),
)

/**
 * What the integration offers this user. Each is a revision (or an object that only gains optional
 * fields); null means not supported, for this user: an administrator's commands are left out for
 * everyone else. Capabilities this client doesn't know are ignored.
 */
@Serializable
data class TerrazzoCapabilities(
  /** The `terrazzo/edit/` commands: dashboard edit sessions, drafts and their renders. */
  val edit: Int? = null,
  /** `terrazzo/push/{register,take,watch}`: the app's silent push. */
  val push: Int? = null,
  /** `terrazzo/design_selections/{get,set}`: per-card design choices, shared across devices. */
  @SerialName("design_selections") val designSelections: Int? = null,
  /** `terrazzo/design_server`: whether the card design add-on runs. */
  @SerialName("design_server") val designServer: TerrazzoDesignServerCapability? = null,
  /** `terrazzo/web_origin`: asking HA to allow this page's origin (CORS). */
  @SerialName("web_origin") val webOrigin: Int? = null,
  /** `terrazzo/agent/info`: the dashboard agent (2: Home Assistant's own, with its name). */
  val agent: Int? = null,
  /** The MCP server: told only to administrators and the one user it serves. */
  val mcp: TerrazzoMcpCapability? = null,
  /** The wall display API's path version, such as `v1` (`/api/terrazzo/wall/v1`). */
  val wall: String? = null,
  /** `terrazzo/edit/{apply,undo}`: an administrator's. */
  @SerialName("edit_apply") val editApply: Int? = null,
  /** `terrazzo/design_server/open`: an administrator's. */
  @SerialName("design_server_open") val designServerOpen: Int? = null,
)

/**
 * The design add-on as the integration last found it; `terrazzo/design_server` asks afresh and
 * answers where to reach it.
 */
@Serializable
data class TerrazzoDesignServerCapability(
  val available: Boolean = false,
  val version: String? = null,
)

/** Whether the integration's MCP server is on (it serves one chosen user). */
@Serializable data class TerrazzoMcpCapability(val enabled: Boolean = false)

/** Whether, and how, this Home Assistant has the Terrazzo integration; see [detectTerrazzo]. */
sealed interface TerrazzoIntegration {
  /** Installed and new enough: [info] says what it offers this user. */
  data class Installed(val info: TerrazzoInfo) : TerrazzoIntegration

  /** Not installed. */
  data object Absent : TerrazzoIntegration

  /**
   * Installed (at [version], when its manifest has one) but with no `terrazzo/info`: a release
   * older than the handshake, or one whose config entry isn't set up, so none of its commands are
   * registered.
   */
  data class TooOld(val version: String?) : TerrazzoIntegration

  /** Installed, but it serves only clients of protocol [minClient] or newer. */
  data class Unsupported(val minClient: Int) : TerrazzoIntegration
}

/** The client protocol this library speaks; see [TerrazzoInfo.minClient]. */
const val TERRAZZO_CLIENT_PROTOCOL = 1

/**
 * Asks the Terrazzo integration what it offers, in one round trip: `terrazzo/info`. An integration
 * older than that command answers `unknown_command`; then Core's `manifest/get` tells an older
 * integration ([TerrazzoIntegration.TooOld]) from none ([TerrazzoIntegration.Absent]). Other
 * refusals fail with [HaCommandException].
 */
suspend fun HaClient.detectTerrazzo(
  clientProtocol: Int = TERRAZZO_CLIENT_PROTOCOL,
): TerrazzoIntegration =
  detectTerrazzo(
    clientProtocol,
    info = { terrazzoInfoJson() },
    manifest = { integrationManifest(TERRAZZO_DOMAIN) },
  )

private const val TERRAZZO_DOMAIN = "terrazzo"

private val infoJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

/** Decodes a `terrazzo/info` answer, ignoring what this client doesn't know. */
fun terrazzoInfoOf(result: JsonObject): TerrazzoInfo =
  infoJson.decodeFromJsonElement(TerrazzoInfo.serializer(), result)

/** [detectTerrazzo]'s rules, over the two commands it may send. */
internal suspend fun detectTerrazzo(
  clientProtocol: Int,
  info: suspend () -> JsonObject,
  manifest: suspend () -> JsonObject,
): TerrazzoIntegration {
  val answer =
    try {
      info()
    } catch (refused: HaCommandException) {
      if (refused.code != "unknown_command") throw refused
      return try {
        TerrazzoIntegration.TooOld(manifest()["version"]?.jsonPrimitive?.contentOrNull)
      } catch (missing: HaCommandException) {
        // Core answers `not_found` for an integration it can't load.
        if (missing.code == "not_found") TerrazzoIntegration.Absent else throw missing
      }
    }
  val decoded = terrazzoInfoOf(answer)
  return if (decoded.minClient > clientProtocol) TerrazzoIntegration.Unsupported(decoded.minClient)
  else TerrazzoIntegration.Installed(decoded)
}
