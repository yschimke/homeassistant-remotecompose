import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.UiBuilderCatalogFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** This consumer links only the public builder service, without app classes or adapters. */
class PublishedCatalogConsumerTest {
  @Test
  fun `the delivered catalog composes all adapted records and declared structural helpers`() {
    val directory = Path.of(requireNotNull(System.getProperty("typedCatalogDirectory")))
    val records =
      Json.decodeFromString(
        ComponentRecordFile.serializer(),
        Files.readString(directory.resolve("components.json")),
      )
    for (record in records.components) {
      assertNull(
        javaClass.classLoader.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class")
      )
    }
    val text = Files.readString(directory.resolve("ui-builder.json"))
    val policy = Json.decodeFromString(UiBuilderCatalogFile.serializer(), text).statusSemantics
    val composed =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(text, records, ExportCapabilitiesV1.Builder().build())
      )
    assertEquals(
      policy.components.keys + policy.builtins.keys,
      composed.catalog.components.map { it.componentId }.toSet(),
    )
    val wasmSupported = JsonPrimitive(System.getProperty("wasmSupported").toBoolean())
    for ((id, component) in policy.components) {
      val capability = composed.catalog.components.single { it.componentId == id }
      assertEquals(component.canvas, capability.wasm.canvas)
      assertEquals(wasmSupported, capability.wasm.platformSupported)
      assertEquals(component.record, composed.records.getValue(id).canonicalId)
    }
    for (template in policy.templates) assertTrue(Files.isRegularFile(directory.resolve(template)))
  }
}
