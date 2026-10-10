@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.adaptivepilot

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat

/** Real Compose captures of the hand-authored app, independent of the UID reference renderer. */
fun main(args: Array<String>) {
  val captures =
    Json.parseToJsonElement(File(args[0]).readText()).jsonObject.getValue("captures").jsonArray
  val out = File(args[1]).apply { mkdirs() }
  SwingUtilities.invokeAndWait {
    for (entry in captures) {
      val capture = entry.jsonObject
      val id = capture.getValue("previewId").jsonPrimitive.content
      val width = capture.getValue("widthDp").jsonPrimitive.int
      val height = capture.getValue("heightDp").jsonPrimitive.int
      val density = capture.getValue("density").jsonPrimitive.float
      val dark = capture.getValue("theme").jsonPrimitive.content == "dark"
      val detail = capture.getValue("state").jsonPrimitive.content == "detail"
      val selected =
        capture.getValue("candidateState").jsonObject.getValue("selectedIndex").jsonPrimitive.int
      val scene =
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
          DashboardBrowser(dark, detail, initialSelectedIndex = selected)
        }
      try {
        // Let layout and pane-expansion effects settle; capture at a fixed animation time.
        repeat(4) { scene.render(it * 1_000_000_000L).close() }
        scene.render(5_000_000_000L).use { image ->
          image.encodeToData(EncodedImageFormat.PNG)!!.use {
            File(out, "$id.png").writeBytes(it.bytes)
          }
        }
        println(id)
      } finally {
        scene.close()
      }
    }
  }
}
