package ee.schimke.ha.devicepacks

import ee.schimke.livery.uibuilder.capture.UiBuilderCapture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HaCardsUiBuilderCatalogTest {
  private val catalog = HaCardsUiBuilderCatalog()

  private fun entityIds(document: JsonObject): Set<String> =
    document
      .getValue("nodes")
      .jsonObject
      .values
      .mapNotNull { it.jsonObject["properties"]?.jsonObject?.get("entityId")?.jsonObject }
      .map { it.getValue("value").jsonPrimitive.content }
      .toSet()

  @Test
  fun isFoundOnTheClasspath() {
    assertEquals(
      listOf("HaCardsUiBuilderCatalog"),
      UiBuilderCapture.catalogs().map { it::class.simpleName },
    )
  }

  @Test
  fun namesTheExampleEntityAsTheContractsSlot() {
    val pack = File("../card-designs/ui-builder/packs/garage-door/controls-skeuomorphic.uid")
    val document = Json.parseToJsonElement(pack.readText()).jsonObject
    assertEquals(setOf("cover.garage_door"), entityIds(document))
    assertEquals(setOf("cover.s0"), entityIds(catalog.prepare(document)))
  }

  @Test
  fun everyPackDesignHasItsComponents() {
    val problems =
      File("../card-designs/ui-builder/packs")
        .walkTopDown()
        .filter { it.extension == "uid" }
        .sortedBy { it.path }
        .flatMap { uid ->
          UiBuilderCapture.design(uid.readText(), listOf(catalog)).validate().map {
            "${uid.name}: $it"
          }
        }
        .toList()
    assertEquals(emptyList(), problems)
  }
}
