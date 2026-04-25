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
      return AddonConfig(
        port = port,
        haBaseUrl = baseUrl,
        haAccessToken = token,
        logLevel = logLevel,
      )
    }
  }
}
