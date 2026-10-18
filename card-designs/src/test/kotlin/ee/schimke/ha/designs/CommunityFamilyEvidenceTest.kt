package ee.schimke.ha.designs

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.ha.previews.*
import ee.schimke.ha.rc.components.HaUberTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Size-bounded render of every published family page, independent of CLI device/filter handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w500dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityFamilyEvidenceTest {
  @get:Rule val compose = createComposeRule()

  private data class Page(
    val name: String,
    val width: Int,
    val height: Int,
    val content: @Composable () -> Unit,
  )

  private val pages =
    listOf(
      Page("Community_Mushroom_Light_render", 360, 640) { Community_Mushroom_Light() },
      Page("Community_Mushroom_Dark_render", 360, 640) { Community_Mushroom_Dark() },
      Page("CommunityDials_Mushroom_render", 360, 600) { CommunityDials_Mushroom() },
      Page("CommunityCards_Mushroom_render", 381, 600) { CommunityCards_Mushroom() },
      Page("CommunityCompact_Mushroom_render", 304, 200) { CommunityCompact_Mushroom() },
      Page("CommunityLight_Mushroom_render", 360, 320) { CommunityLight_Mushroom() },
      Page("CommunityMedia_Mushroom_render", 381, 600) { CommunityMedia_Mushroom() },
      Page("Community_Bubble_Light_render", 360, 640) { Community_Bubble_Light() },
      Page("Community_Bubble_Dark_render", 360, 640) { Community_Bubble_Dark() },
      Page("CommunityDials_Bubble_render", 360, 600) { CommunityDials_Bubble() },
      Page("CommunityCards_Bubble_render", 381, 600) { CommunityCards_Bubble() },
      Page("CommunityCompact_Bubble_render", 304, 200) { CommunityCompact_Bubble() },
      Page("CommunityLight_Bubble_render", 360, 320) { CommunityLight_Bubble() },
      Page("CommunityMedia_Bubble_render", 381, 600) { CommunityMedia_Bubble() },
      Page("Community_Minimalist_Light_render", 360, 640) { Community_Minimalist_Light() },
      Page("Community_Minimalist_Dark_render", 360, 640) { Community_Minimalist_Dark() },
      Page("CommunityDials_Minimalist_render", 360, 600) { CommunityDials_Minimalist() },
      Page("CommunityCards_Minimalist_render", 381, 600) { CommunityCards_Minimalist() },
      Page("CommunityCompact_Minimalist_render", 304, 200) { CommunityCompact_Minimalist() },
      Page("CommunityLight_Minimalist_render", 360, 320) { CommunityLight_Minimalist() },
      Page("CommunityMedia_Minimalist_render", 381, 600) { CommunityMedia_Minimalist() },
      Page("Community_Cupertino_Light_render", 360, 640) { Community_Cupertino_Light() },
      Page("Community_Cupertino_Dark_render", 360, 640) { Community_Cupertino_Dark() },
      Page("CommunityDials_Cupertino_render", 360, 600) { CommunityDials_Cupertino() },
      Page("CommunityCards_Cupertino_render", 381, 600) { CommunityCards_Cupertino() },
      Page("CommunityCompact_Cupertino_render", 304, 200) { CommunityCompact_Cupertino() },
      Page("CommunityLight_Cupertino_render", 360, 320) { CommunityLight_Cupertino() },
      Page("CommunityMedia_Cupertino_render", 381, 600) { CommunityMedia_Cupertino() },
      Page("Community_Catppuccin_Light_render", 360, 640) { Community_Catppuccin_Light() },
      Page("Community_Catppuccin_Dark_render", 360, 640) { Community_Catppuccin_Dark() },
      Page("CommunityDials_Catppuccin_render", 360, 600) { CommunityDials_Catppuccin() },
      Page("CommunityCards_Catppuccin_render", 381, 600) { CommunityCards_Catppuccin() },
      Page("CommunityCompact_Catppuccin_render", 304, 200) { CommunityCompact_Catppuccin() },
      Page("CommunityLight_Catppuccin_render", 360, 320) { CommunityLight_Catppuccin() },
      Page("CommunityMedia_Catppuccin_render", 381, 600) { CommunityMedia_Catppuccin() },
      Page("Community_Obsidian_Light_render", 360, 640) { Community_Obsidian_Light() },
      Page("Community_Obsidian_Dark_render", 360, 640) { Community_Obsidian_Dark() },
      Page("CommunityDials_Obsidian_render", 360, 600) { CommunityDials_Obsidian() },
      Page("CommunityCards_Obsidian_render", 381, 600) { CommunityCards_Obsidian() },
      Page("CommunityCompact_Obsidian_render", 304, 200) { CommunityCompact_Obsidian() },
      Page("CommunityLight_Obsidian_render", 360, 320) { CommunityLight_Obsidian() },
      Page("CommunityMedia_Obsidian_render", 381, 600) { CommunityMedia_Obsidian() },
      Page("Community_Noctis_Light_render", 360, 640) { Community_Noctis_Light() },
      Page("Community_Noctis_Dark_render", 360, 640) { Community_Noctis_Dark() },
      Page("CommunityDials_Noctis_render", 360, 600) { CommunityDials_Noctis() },
      Page("CommunityCards_Noctis_render", 381, 600) { CommunityCards_Noctis() },
      Page("CommunityCompact_Noctis_render", 304, 200) { CommunityCompact_Noctis() },
      Page("CommunityLight_Noctis_render", 360, 320) { CommunityLight_Noctis() },
      Page("CommunityMedia_Noctis_render", 381, 600) { CommunityMedia_Noctis() },
      Page("Community_Lcars_Light_render", 360, 640) { Community_Lcars_Light() },
      Page("Community_Lcars_Dark_render", 360, 640) { Community_Lcars_Dark() },
      Page("CommunityDials_Lcars_render", 360, 600) { CommunityDials_Lcars() },
      Page("CommunityCards_Lcars_render", 381, 600) { CommunityCards_Lcars() },
      Page("CommunityCompact_Lcars_render", 304, 200) { CommunityCompact_Lcars() },
      Page("CommunityLight_Lcars_render", 360, 320) { CommunityLight_Lcars() },
      Page("CommunityMedia_Lcars_render", 381, 600) { CommunityMedia_Lcars() },
      Page("Community_Arctic_Light_render", 360, 640) { Community_Arctic_Light() },
      Page("Community_Arctic_Dark_render", 360, 640) { Community_Arctic_Dark() },
      Page("CommunityDials_Arctic_render", 360, 600) { CommunityDials_Arctic() },
      Page("CommunityCards_Arctic_render", 381, 600) { CommunityCards_Arctic() },
      Page("CommunityCompact_Arctic_render", 304, 200) { CommunityCompact_Arctic() },
      Page("CommunityLight_Arctic_render", 360, 320) { CommunityLight_Arctic() },
      Page("CommunityMedia_Arctic_render", 381, 600) { CommunityMedia_Arctic() },
      Page("Community_Aeon_Light_render", 360, 640) { Community_Aeon_Light() },
      Page("Community_Aeon_Dark_render", 360, 640) { Community_Aeon_Dark() },
      Page("CommunityDials_Aeon_render", 360, 600) { CommunityDials_Aeon() },
      Page("CommunityCards_Aeon_render", 381, 600) { CommunityCards_Aeon() },
      Page("CommunityCompact_Aeon_render", 304, 200) { CommunityCompact_Aeon() },
      Page("CommunityLight_Aeon_render", 360, 320) { CommunityLight_Aeon() },
      Page("CommunityMedia_Aeon_render", 381, 600) { CommunityMedia_Aeon() },
    )

  @Test
  fun everyFamilyPageRendersWithinTheImageLimit() {
    var page by mutableStateOf(pages.first())
    compose.setContent {
      key(page.name) {
        Box(Modifier.size(page.width.dp, page.height.dp).clipToBounds().testTag("page")) {
          page.content()
        }
      }
    }
    val output = File(requireNotNull(System.getProperty("cardDesignsOut")), "community-renders")
    output.mkdirs()
    for (next in pages) {
      compose.runOnIdle { page = next }
      compose.waitForIdle()
      val image = compose.onNodeWithTag("page").captureToImage().asAndroidBitmap()
      assertTrue(image.width <= 1800 && image.height <= 1800, next.name)
      if (next.name == "CommunityCards_Aeon_render") {
        // The fixture's early-day solar samples are zero. Empty framed columns must stay empty,
        // rather than forming a solid marquee-colour block across the night-time samples.
        val accent = HaUberTheme.Aeon.palette(false).linkText.toArgb()
        var filled = 0
        for (y in 324 until 338) for (x in 50 until 200) {
          if (image.getPixel(x, y) == accent) filled++
        }
        assertTrue(
          filled < 150 * 14 / 2,
          "zero solar samples must not become a solid block: $filled",
        )
      }
      if (next.name == "CommunityDials_Arctic_render") {
        // Keep the control's horizon out of the central numeric readout window.
        val surface = HaUberTheme.Arctic.palette(false).cardBackground.toArgb()
        for (y in 132..133) {
          val row = (120 until 240).map { x -> image.getPixel(x, y) }.filter { it != surface }
          val longestColour = row.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
          assertTrue(longestColour < 60, "a horizon must not strike through the control reading")
        }
      }
      // A surface alone is not a successful card capture. Require visible content variation.
      val colors = HashSet<Int>()
      for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
        colors.add(image.getPixel(x, y))
      }
      assertTrue(colors.size > 15, "${next.name} must contain rendered card content")
      File(output, "${next.name}.png").outputStream().use {
        image.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }
}
