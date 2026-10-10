package ee.schimke.ha.catalog

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogRenderTest {
  @get:Rule val compose = createComposeRule()

  private fun scope(
    id: String,
    properties: JsonObject = JsonObject(emptyMap()),
    content: @Composable () -> Unit = {},
  ) =
    CanvasNodeScope(
      UiBuilderNode(id = "node", componentId = id, properties = properties),
      Modifier,
      CanvasMode.Device,
      { _, _ -> content() },
      { _, _ -> },
      { 0 },
      { _, _, _ -> },
      {},
      { _, _ -> },
      {},
    )

  @Test
  fun `native Remote Compose heading and typed adapter render the same pixels`() {
    val catalog = HomeAssistantCatalog(discoverCatalogComponents())
    val adapter = requireNotNull(catalog.registry()[catalog.heading.id])
    var adapted by mutableStateOf(false)
    compose.setContent {
      MaterialTheme {
        Box(Modifier.size(400.dp, 160.dp).testTag("catalog")) {
          if (!adapted) CatalogHeading("Kitchen")
          else
            adapter(
              scope(
                catalog.heading.id,
                buildJsonObject {
                  put(
                    "title",
                    buildJsonObject {
                      put("type", "string")
                      put("value", "Kitchen")
                    },
                  )
                },
              )
            )
        }
      }
    }
    compose.waitForIdle()
    val directImage = compose.onNodeWithTag("catalog").captureToImage()
    val directory =
      File(requireNotNull(System.getProperty("typedCatalogRenders"))).also { it.mkdirs() }
    directory.resolve("native-heading.png").outputStream().use {
      directImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val direct = directImage.toPixelMap()
    compose.runOnIdle { adapted = true }
    compose.waitForIdle()
    val typedImage = compose.onNodeWithTag("catalog").captureToImage()
    directory.resolve("typed-heading.png").outputStream().use {
      typedImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    val typed = typedImage.toPixelMap()
    assertEquals(direct.width, typed.width)
    assertEquals(direct.height, typed.height)
    val colors = mutableSetOf<androidx.compose.ui.graphics.Color>()
    for (y in 0 until direct.height) for (x in 0 until direct.width) {
      colors += direct[x, y]
      assertEquals(direct[x, y], typed[x, y], "pixel $x,$y")
    }
    assertTrue(colors.size > 2, "the native player must draw real heading content")
  }

  @Test
  fun `actual app stack receives catalog slot children`() {
    val catalog = HomeAssistantCatalog(discoverCatalogComponents())
    val adapter = requireNotNull(catalog.registry()[catalog.stack.id])
    compose.setContent { adapter(scope(catalog.stack.id) { Text("Catalog slot child") }) }
    compose.onNodeWithText("Catalog slot child").assertExists()
  }
}
