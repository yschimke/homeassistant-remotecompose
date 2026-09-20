package ee.schimke.ha.client

import ee.schimke.ha.model.Dashboard
import ee.schimke.ha.model.HaSnapshot

/**
 * Narrow multiplatform facade intended for native application hosts.
 *
 * Unlike [HaClient]'s dependency-injection constructor, this API does not expose Ktor engine types
 * across the Kotlin/Native boundary. The target selects its installed engine: OkHttp on Android,
 * CIO on JVM, and NSURLSession-backed Darwin on iOS. Kotlin `suspend` functions export as Swift
 * async functions in the generated `TerrazzoKit` framework.
 *
 * OAuth presentation, secure token storage, and local-network discovery intentionally remain
 * platform owned. Once Swift supplies an access token, command correlation, WebSocket auth, JSON
 * decoding, and dashboard/state fetching stay shared here.
 */
class TerrazzoClient(baseUrl: String, accessToken: String) {
  private val client = HaClient(HaConfig(baseUrl = baseUrl.trimEnd('/'), accessToken = accessToken))

  suspend fun connect() = client.connect()

  suspend fun listDashboards(): List<DashboardSummary> = client.listDashboards()

  suspend fun loadDashboard(urlPath: String?): Dashboard = client.fetchDashboard(urlPath)

  suspend fun loadSnapshot(): HaSnapshot = client.snapshot()

  suspend fun callService(
    domain: String,
    service: String,
    entityId: String? = null,
  ) = client.callService(domain = domain, service = service, entityId = entityId)

  suspend fun close() = client.close()
}
