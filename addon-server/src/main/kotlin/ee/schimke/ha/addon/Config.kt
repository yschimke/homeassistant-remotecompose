package ee.schimke.ha.addon

/**
 * Runtime configuration, assembled from env + add-on options.
 *
 * HA supervisor contract for add-ons: `SUPERVISOR_TOKEN` is injected by the supervisor when
 * `hassio_api` / `homeassistant_api` is requested in `config.yaml`. When that's present we address
 * Core at `http://supervisor/core` (REST) and `ws://supervisor/core/websocket` (WS). For local
 * development outside the supervisor we fall through to `HA_BASE_URL` + `HA_TOKEN` — same shape
 * `HaClient` already uses.
 */
data class AddonConfig(
  val port: Int,
  val haBaseUrl: String,
  val haAccessToken: String,
  val logLevel: String,
  /** Set when running as an add-on; enables ingress trust and Core URL discovery for auth. */
  val supervisorToken: String? = null,
  /**
   * Home Assistant Core base URL that caller tokens are validated against (`GET <url>/api/`).
   * `HA_AUTH_URL`, else `HA_BASE_URL` (local dev talks to Core directly); null under the
   * supervisor, where it is discovered from `/core/info` because `http://supervisor/core` won't
   * accept user tokens.
   */
  val authBaseUrl: String? = null,
) {
  companion object {
    private const val SUPERVISOR_URL = "http://supervisor/core"

    fun fromEnv(env: Map<String, String> = System.getenv()): AddonConfig {
      val supervisor = env["SUPERVISOR_TOKEN"]
      val baseUrl =
        env["HA_BASE_URL"]
          ?: if (supervisor != null) SUPERVISOR_URL
          else
            error("no HA endpoint: set SUPERVISOR_TOKEN (add-on) or HA_BASE_URL+HA_TOKEN (local)")
      val token =
        supervisor ?: env["HA_TOKEN"] ?: error("no HA auth: set SUPERVISOR_TOKEN or HA_TOKEN")
      val port = env["PORT"]?.toIntOrNull() ?: 8099
      val logLevel = env["LOG_LEVEL"] ?: "info"
      val authBaseUrl = env["HA_AUTH_URL"] ?: env["HA_BASE_URL"]
      return AddonConfig(
        port = port,
        haBaseUrl = baseUrl,
        haAccessToken = token,
        logLevel = logLevel,
        supervisorToken = supervisor,
        authBaseUrl = authBaseUrl,
      )
    }
  }
}
