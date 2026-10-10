package ee.schimke.catalog.host

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** This host compiles with the Android engines and SDK, without any Home Assistant module. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExternalAndroidCatalogEndToEndTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `installed Android catalog renders a saved design edits its heading and reopens`() {
    val bundle = File(requireNotNull(System.getProperty("typedCatalogAndroidBundle")))
    val evidence =
      File(requireNotNull(System.getProperty("typedCatalogRenders"))).also { it.mkdirs() }
    val records =
      Json.decodeFromString<ComponentRecordFile>(bundle.resolve("components.json").readText())
    val policy = Json.parseToJsonElement(bundle.resolve("ui-builder.json").readText()).jsonObject
    assertEquals(
      "homeassistant-remotecompose",
      policy.getValue("catalog").jsonObject.getValue("id").jsonPrimitive.content,
    )
    val ids = policy.getValue("statusSemantics").jsonObject.getValue("components").jsonObject.keys
    assertEquals(
      setOf("homeassistant-remotecompose/heading", "homeassistant-remotecompose/vertical-stack"),
      ids,
    )
    val host = javaClass.classLoader
    for (record in records.components) {
      assertNull(
        host.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class"),
        "app code must not enter the host classpath",
      )
    }
    val jars = requireNotNull(bundle.resolve("lib").listFiles()).sortedBy { it.name }
    assertEquals(3, jars.size)
    var active by mutableStateOf(true)
    val loader = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), host)
    // Keep the loader alive until the test rule disposes the Android composition.
    try {
      for (record in records.components) {
        assertEquals(
          "jar",
          assertNotNull(loader.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class"))
            .protocol,
        )
      }
      val providers =
        ServiceLoader.load(JvmCanvasAdapterProvider::class.java, loader).filter {
          it.catalogId == "homeassistant-remotecompose"
        }
      assertEquals(1, providers.size)
      val appRegistry = providers.single().registry(records)
      ids.forEach { assertNotNull(appRegistry[it]) }
      val registry =
        appRegistry +
          canvasAdapterRegistry {
            register("host/text") {
              Text(
                string("text"),
                modifier = modifier,
                color = MaterialTheme.colorScheme.onBackground,
              )
            }
          }
      val saved =
        Json.decodeFromString<UiBuilderDocument>(bundle.resolve("designs/heading.uid").readText())
      var document by mutableStateOf(saved)
      var opened by mutableIntStateOf(0)
      compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
          Box(Modifier.size(400.dp, 160.dp).background(Color(0xFF202020)).testTag("catalog")) {
            if (active)
              key(opened) {
                CanvasDocumentHost(
                  document,
                  ids.associateWith { it } + ("host/text" to "host/text"),
                  emptyMap(),
                  CanvasMode.Device,
                  LocalDensity.current,
                ) { entry, modifier ->
                  RenderCanvasNode(
                    entry,
                    registry,
                    modifier,
                    applyModifier = { next, value ->
                      next.applyCanvasModifier(
                        value,
                        CanvasMode.Device,
                        resolveColor = { error("unsupported fixture color $it") },
                        resolveShape = { RectangleShape },
                      )
                    },
                    missingComponent = { id, _ -> error("missing component $id") },
                    fallback = { error("missing adapter $adapterId") },
                  )
                }
              }
          }
        }
      }
      compose.onNodeWithText("Published slot child").assertIsDisplayed()
      compose.waitForIdle()
      val before = compose.onNodeWithTag("catalog").captureToImage()
      fun write(name: String) {
        evidence.resolve(name).outputStream().use {
          compose
            .onNodeWithTag("catalog")
            .captureToImage()
            .asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
      }
      write("installed-heading.png")
      compose.runOnIdle {
        val heading = document.nodes.getValue("heading")
        document =
          document.copy(
            revision = document.revision + 1,
            nodes =
              document.nodes +
                (heading.id to
                  heading.copy(
                    properties =
                      buildJsonObject {
                        put(
                          "title",
                          buildJsonObject {
                            put("type", "string")
                            put("value", "Living room")
                          },
                        )
                      }
                  )),
          )
      }
      compose.waitForIdle()
      val after = compose.onNodeWithTag("catalog").captureToImage()
      val a = before.toPixelMap()
      val b = after.toPixelMap()
      val changed =
        (0 until a.height).sumOf { y -> (0 until a.width).count { x -> a[x, y] != b[x, y] } }
      assertTrue(changed > 20, "changing the real Remote Compose heading must redraw its pixels")
      compose.onNodeWithText("Published slot child").assertIsDisplayed()
      write("edited-heading.png")
      compose.runOnIdle {
        val file = evidence.resolve("edited-heading.uid")
        file.writeText(Json.encodeToString(UiBuilderDocument.serializer(), document))
        val reopened = Json.decodeFromString<UiBuilderDocument>(file.readText())
        assertEquals(document, reopened)
        assertEquals(1, reopened.revision)
        assertEquals(
          "Living room",
          reopened.nodes
            .getValue("heading")
            .properties
            .getValue("title")
            .jsonObject
            .getValue("value")
            .jsonPrimitive
            .content,
        )
        document = reopened
        opened++
      }
      compose.waitForIdle()
      val reopened = compose.onNodeWithTag("catalog").captureToImage().toPixelMap()
      for (y in 0 until b.height) for (x in 0 until b.width) assertEquals(
        b[x, y],
        reopened[x, y],
        "reopened pixel $x,$y",
      )
    } finally {
      compose.runOnIdle { active = false }
      compose.waitForIdle()
      loader.close()
    }
  }
}
