@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.adaptivepilot

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat

/** Real Compose captures of the hand-authored app, independent of the UID reference renderer. */
fun main(args: Array<String>) {
  val out = File(args.single()).apply { mkdirs() }
  SwingUtilities.invokeAndWait {
    for (width in listOf(412, 840)) {
      for (dark in listOf(false, true)) {
        for (detail in listOf(false, true)) {
          val id =
            "dashboards-$width-${if (detail) "detail" else "list"}-${if (dark) "dark" else "light"}"
          val scene =
            ImageComposeScene(width * 2, 1440, Density(2f)) { DashboardBrowser(dark, detail) }
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
  }
}
