package ee.schimke.ha.designserver

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CatalogSourcesTest {
  @Test
  fun everyServedBuilderCatalogHasAPublishedSource() {
    val config =
      Json.parseToJsonElement(File(System.getProperty("designServer.catalogs")).readText())
        .jsonObject
    val sources =
      config.getValue("catalogs").jsonArray.associate {
        val source = it.jsonObject
        source.getValue("system").jsonPrimitive.content to
          source.getValue("repo").jsonPrimitive.content
      }
    val enabled = config.getValue("uiBuilder").jsonObject.getValue("catalogs").jsonObject
    for ((id, settings) in enabled) {
      if (settings.jsonObject["serve"]?.jsonPrimitive?.booleanOrNull == true) {
        assertTrue(
          !sources[id].isNullOrBlank(),
          "$id is enabled in UI Builder but has no published catalog source",
        )
      }
    }
  }
}
