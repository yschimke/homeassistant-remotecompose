package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.*
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.client.design.DesignPublisher
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class CatalogDesignsTest {
  private val template = javaClass.getResource("/tile-light.rc")!!.readBytes()
  private val key =
    DesignKey(
      "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = 8,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )
  private val source =
    Json.parseToJsonElement(
        """{"id":"editable-source","revision":4,"catalogPin":{"systemId":"ha-cards","catalogRevision":"sha256:real","nativeRuntimeId":"runtime-v1","capabilityDigest":"candidate"},"nodes":{"root":{"componentId":"ha-cards/tile"}}} """
      )
      .jsonObject

  private fun directory() = Files.createTempDirectory("catalog-designs").toFile()

  private fun library(): DesignLibrary {
    val dir = directory()
    DesignPublisher.publish(listOf(BuiltDesign(key, 1, template)), serial = 5, nowEpochMs = 1_000)
      .files
      .forEach { (path, bytes) ->
        File(dir, path).also {
          it.parentFile.mkdirs()
          it.writeBytes(bytes)
        }
      }
    return DesignLibrary.load(dir)
  }

  @Test
  fun durableHistoryCasAndRecovery() {
    val dir = directory()
    val store = CatalogDesignStore(dir)
    fun publish(expected: Long, doc: JsonObject = source) =
      store.publish(
        "new-design",
        expected,
        doc,
        key,
        template,
        1,
        null,
        "github:admin",
        "base",
        2_000,
      )
    val first = publish(0)
    assertEquals(1, first.revision)
    assertFailsWith<IllegalStateException> { publish(0) }
    val second = publish(1, JsonObject(source + ("title" to JsonPrimitive("Revision two"))))
    assertEquals(2, second.revision)
    val reopened = CatalogDesignStore(dir)
    assertEquals(listOf(first, second), reopened.snapshot().revisions)
    assertContentEquals(template, reopened.blob(first.entry.sha256))
    val restored =
      reopened.publish(
        "new-design",
        2,
        first.source,
        key,
        template,
        1,
        null,
        "github:admin",
        "base",
      )
    assertEquals(3, restored.revision)
    assertEquals(source, restored.source)
    assertEquals(3, reopened.snapshot().serial)
  }

  @Test
  fun discoveryOverlaysOnlyPublishedKeysAndKeepsFallback() {
    val store = CatalogDesignStore(directory())
    val base = library()
    val different = key.copy(theme = "style:brand-new:light")
    store.publish(
      "brand-new",
      0,
      source,
      different,
      template,
      1,
      null,
      "github:admin",
      "base",
      2_000,
    )
    val live = store.overlay(base)
    assertEquals(setOf(key, different), live.designs.map { it.key }.toSet())
    assertEquals(6, live.manifest.serial)
    assertNotNull(live.design("brand-new"))
    assertContentEquals(template, live.blob(live.design("brand-new")!!.entry.sha256))
    assertEquals(source, store.source("brand-new"))
    assertFailsWith<IllegalStateException> {
      store.publish("other-owner", 0, source, different, template, 1, null, "github:admin", "base")
    }
  }

  @Test
  fun corruptDurableArtifactIsNotSilentlyAcceptedOnRestart() {
    val dir = directory()
    val store = CatalogDesignStore(dir)
    val revision =
      store.publish("new-design", 0, source, key, template, 1, null, "github:admin", "base")
    File(dir, "blobs/${revision.entry.sha256}.rc").writeText("broken")
    assertFailsWith<IllegalStateException> { CatalogDesignStore(dir) }
  }

  @Test
  fun newEntityColorBindingsCannotBypassTheLiveHostContract() {
    val document = RcDocumentCodec.decode(template, RcWireLimits())
    fun withColor(name: String) =
      RcDocumentCodec.encode(
        document.copy(
          operations = document.operations + RcNamedVariable(9000, RcNamedVariable.COLOR_TYPE, name)
        )
      )
    assertEquals(
      "Unknown host binding",
      catalogTemplateProblem(withColor("light.s0.new_color"), template, key),
    )
    assertNull(catalogTemplateProblem(withColor("WearM3.primary"), template, key))
  }

  @Test
  fun sourceAndTemplateValidationFailClosed() {
    assertNull(catalogSourceProblem(source))
    assertNotNull(catalogSourceProblem(JsonObject(source - "catalogPin")))
    assertNotNull(
      catalogSourceProblem(
        JsonObject(source + ("assets" to buildJsonObject { put("logo", "external") }))
      )
    )
    assertNotNull(catalogTemplateProblem(byteArrayOf(1, 2), template, key))
    assertNull(catalogTemplateProblem(template, template, key))
    assertNotNull(
      catalogTemplateProblem(javaClass.getResource("/gauge-unit.rc")!!.readBytes(), template, key)
    )
  }
}
