package ee.schimke.ha.designserver

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.serialization.json.*

internal fun Route.studioCatalogFixture(failFirstLookup: Boolean = false) {
  val lookups = AtomicInteger()
  post("/api/ui-builder/v1/requests") {
    if (failFirstLookup && lookups.incrementAndGet() == 1) {
      call.respondText("private catalog error", status = HttpStatusCode.ServiceUnavailable)
      return@post
    }
    assertEquals("operator", call.request.queryParameters["token"])
    val request = Json.parseToJsonElement(call.receiveText()).jsonObject
    assertEquals(JsonPrimitive("operator"), request["actorId"])
    assertEquals(JsonPrimitive("listCatalogs"), request["request"]!!.jsonObject["type"])
    call.respondText(
      """{"response":{"type":"catalogs","catalogs":[{"benchmark":{"catalogSystemId":"ha-cards","catalogRevision":"sha256:published","nativeRuntimeId":"published-runtime"}}]}}"""
    )
  }
}
