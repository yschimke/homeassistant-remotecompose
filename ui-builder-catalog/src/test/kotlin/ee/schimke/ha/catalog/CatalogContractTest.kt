package ee.schimke.ha.catalog

import ee.schimke.composeai.discovery.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CatalogContractTest {
  @Test
  fun `fresh app metadata generates a portable explicitly selected catalog`() {
    val records = discoverCatalogComponents()
    val adapters = HomeAssistantCatalog(records)
    adapters.definitions.forEach { definition ->
      assertEquals(
        emptyList(),
        definition.validateAgainst(
          records.components.single { it.canonicalId == definition.component.canonicalId }
        ),
      )
    }
    val pair = adapters.generate(records)
    assertEquals(
      setOf(
        "homeassistant-remotecompose/heading",
        "homeassistant-remotecompose/tile",
        "homeassistant-remotecompose/button",
        "homeassistant-remotecompose/entity-row",
        "homeassistant-remotecompose/entities",
        "homeassistant-remotecompose/glance",
        "homeassistant-remotecompose/vertical-stack",
        "homeassistant-remotecompose/horizontal-stack",
      ),
      pair.catalog.statusSemantics.components.keys,
    )
    assertTrue(pair.catalog.diagnostics.isEmpty())
    val json = Json { encodeDefaults = true }
    val portable =
      json.decodeFromString(
        UiBuilderCatalogFile.serializer(),
        json.encodeToString(UiBuilderCatalogFile.serializer(), pair.catalog),
      )
    assertEquals(pair.catalog, portable)
    val destination = Path.of(requireNotNull(System.getProperty("typedCatalogOutput")))
    destination.toFile().deleteRecursively()
    pair.writeTo(destination)
    assertEquals(
      pair.record,
      json.decodeFromString(
        ComponentRecordFile.serializer(),
        Files.readString(destination.resolve("components.json")),
      ),
    )
    assertEquals(
      pair.catalog,
      json.decodeFromString(
        UiBuilderCatalogFile.serializer(),
        Files.readString(destination.resolve("ui-builder.json")),
      ),
    )
    assertFailsWith<IllegalArgumentException> { pair.writeTo(destination) }
  }

  @Test
  fun `entity lists store nested list and object wrappers and default an omitted state`() {
    val catalog = HomeAssistantCatalog(discoverCatalogComponents())
    val stored =
      Json.parseToJsonElement(
          """
          {"type":"list","values":[
            {"type":"object","fields":{
              "name":{"type":"string","value":"Kitchen"},"state":{"type":"string","value":"On"}}},
            {"type":"object","fields":{"name":{"type":"string","value":"Porch"}}}]}
          """
        )
        .jsonObject
    val expected = listOf(CatalogEntity("Kitchen", "On"), CatalogEntity("Porch"))
    assertEquals(expected, catalog.entities.rows.unwrap(stored))
    assertEquals(expected, catalog.glance.cells.unwrap(stored))
    val wrapped = catalog.entities.rows.codec.wrap(expected)
    assertEquals(expected, catalog.entities.rows.unwrap(wrapped))
    assertEquals(catalog.entities.rows.default, catalog.entities.rows.unwrap(null))
    assertFailsWith<IllegalArgumentException> {
      catalog.entities.rows.unwrap(
        Json.parseToJsonElement(
            """{"type":"list","values":[{"type":"object","fields":{"state":{"type":"string","value":"On"}}}]}"""
          )
          .jsonObject
      )
    }
    assertEquals("ee.schimke.ha.catalog.CatalogEntity", CatalogEntityCodec.typeFqn)
    assertEquals(CatalogEntity::class.qualifiedName, CatalogEntityCodec.typeFqn)
  }

  @Test
  fun `app adapter refuses stale discovery records`() {
    val records = discoverCatalogComponents()
    val catalog = HomeAssistantCatalog(records)
    val stale =
      records
        .newBuilder()
        .also { b ->
          b.components =
            records.components.map { c ->
              c.newBuilder().also { it.signatureKnown = false }.build()
            }
        }
        .build()
    assertFailsWith<IllegalArgumentException> { catalog.generate(stale) }
  }
}
