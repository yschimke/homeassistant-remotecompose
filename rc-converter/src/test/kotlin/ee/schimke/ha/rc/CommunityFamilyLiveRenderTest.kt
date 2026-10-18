@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.components.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pushes values through the real host into the SAME document; no text can mask a frozen meter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityFamilyLiveRenderTest {
  @get:Rule val compose = createComposeRule()
  private val families = HaUberTheme.all.filter { it.description.isNotEmpty() }
  private val kinds =
    listOf(
      HaMeasureKind.Temperature,
      HaMeasureKind.Battery,
      HaMeasureKind.Humidity,
      HaMeasureKind.Percentage,
    )
  private var theme by mutableStateOf(HaUberTheme.Mushroom)
  private var dark by mutableStateOf(false)
  private var level by mutableStateOf(10f)
  private var captures = 0
  private val card =
    CardConfig(
      "entities",
      buildJsonObject {
        put("type", "entities")
        put("entities", JsonArray(kinds.indices.map { JsonPrimitive("sensor.value$it") }))
      },
    )

  private fun snapshot() =
    HaSnapshot(
      states =
        kinds.indices.associate { i ->
          val id = "sensor.value$i"
          id to
            EntityState(id, level.toString(), buildJsonObject { put("unit_of_measurement", "%") })
        }
    )

  private fun frame(name: String): Bitmap {
    compose.waitForIdle()
    val image = compose.onNodeWithTag("meters").captureToImage().asAndroidBitmap()
    assertTrue(image.width <= 1800 && image.height <= 1800)
    System.getProperty("lovelaceRenderEvidence")?.let {
      File(it, "$name.png")
        .also { f -> f.parentFile?.mkdirs() }
        .outputStream()
        .use { stream -> image.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    }
    return image
  }

  @Test
  fun allFourMeterKindsAndSwitchesMoveWithoutRecaptureInBothModes() {
    compose.setContent {
      val currentTheme = theme
      val currentDark = dark
      val key = "community-live-${currentTheme.id}-$currentDark"
      Box(Modifier.width(320.dp).testTag("meters")) {
        CachedCardPreview(
          cacheKey = key,
          profile = androidXExperimentalWrap,
          card = card,
          snapshot = snapshot(),
        ) {
          captures++
          ProvideHaUberTheme(currentTheme, currentDark) {
            RemoteColumn {
              RemoteRow {
                kinds.forEachIndexed { index, kind ->
                  RemoteHaMeasurement(
                    HaReading.live(
                      "sensor.value$index",
                      "Reading",
                      "10 %",
                      10f,
                      "%",
                      kind,
                      min = 0.0,
                      max = 100.0,
                    ),
                    HaPresentation.Glyph,
                    RemoteModifier.size(80.rdp, 100.rdp),
                  )
                }
              }
              RemoteHaToggleSwitchByProgress(
                LiveValues.numericState("sensor.value3", 10f) / 100f.rf,
                currentRemoteHaTheme().linkText,
                currentRemoteHaTheme().divider,
              )
            }
          }
        }
      }
    }
    assertEquals(10, families.size)
    for (family in families) for (mode in listOf(false, true)) {
      compose.runOnIdle {
        theme = family
        dark = mode
        level = 0f
      }
      val low = frame("${family.id}-$mode-low")
      val captured = captures
      compose.runOnIdle { level = 100f }
      val high = frame("${family.id}-$mode-high")
      assertEquals(captured, captures, "${family.id} must update without re-encode")
      for (i in kinds.indices) {
        var changed = 0
        for (y in 0 until minOf(100, low.height)) for (x in i * 80 until (i + 1) * 80) {
          if (low.getPixel(x, y) != high.getPixel(x, y)) changed++
        }
        assertTrue(changed > 5, "${family.id}/$mode ${kinds[i]} moves independently of text")
      }
      var switchChanges = 0
      for (y in 100 until low.height) for (x in 0 until minOf(40, low.width)) {
        if (low.getPixel(x, y) != high.getPixel(x, y)) switchChanges++
      }
      assertTrue(switchChanges > 5, "${family.id}/$mode switch thumb follows live progress")
    }
  }

  @Test
  fun launcherBakesEveryFamilyWithConcreteReadingsAndActions() {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val registry = ee.schimke.ha.rc.cards.defaultRegistry()
    val measurements =
      listOf(
        Triple("temperature", "°C", -10f to 40f),
        Triple("battery", "%", 0f to 100f),
        Triple("humidity", "%", 0f to 100f),
        Triple("", "%", 0f to 100f),
      )
    for (family in families) for (mode in listOf(false, true)) {
      val look = LauncherLook(family, ThemeStyle.TerrazzoHome)
      for ((device, unit, range) in measurements) {
        val id = "sensor.reading"
        val config =
          CardConfig(
            "sensor",
            buildJsonObject {
              put("type", "sensor")
              put("entity", id)
            },
          )
        fun bake(value: Float): LauncherDesigns.Baked {
          val snapshot =
            HaSnapshot(
              states =
                mapOf(
                  id to
                    EntityState(
                      id,
                      value.toString(),
                      buildJsonObject {
                        put("friendly_name", "Reading")
                        put("unit_of_measurement", unit)
                        put("device_class", device)
                      },
                    )
                )
            )
          val baked =
            kotlin.test.assertNotNull(
              LauncherDesigns.render(
                context,
                config,
                snapshot,
                96,
                96,
                160,
                dark = mode,
                systemTheme = false,
                registry = registry,
                look = look,
              ),
              "${family.id}/$device bakes",
            )
          val decoded =
            ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec.decode(
              baked.bytes,
              ee.schimke.composeai.rcplayer.protocol.RcWireLimits(),
            )
          assertTrue(
            decoded.namedVariables.none {
              it.name.endsWith(".state") || it.name.endsWith(".numeric_state")
            },
            "widget readings must be constants",
          )
          val operations = arrayListOf<androidx.compose.remote.core.Operation>()
          androidx.compose.remote.core.RemoteComposeBuffer.fromInputStream(
              platformWidgetDocument(baked.bytes).inputStream()
            )
            .inflateFromBuffer(operations)
          assertTrue(
            operations.none { it is androidx.compose.remote.core.operations.ColorExpression },
            "${family.id}/$device widget colours must fold for the platform player: " +
              operations
                .filterIsInstance<androidx.compose.remote.core.operations.ColorExpression>()
                .joinToString(),
          )
          return baked
        }
        val low = bake(range.first)
        val high = bake(range.second)
        assertTrue(
          !low.bytes.contentEquals(high.bytes),
          "${family.id}/$device rebakes changed values",
        )
      }
      val id = "light.kitchen"
      val config =
        CardConfig(
          "button",
          buildJsonObject {
            put("type", "button")
            put("entity", id)
          },
        )
      val snapshot =
        HaSnapshot(
          states =
            mapOf(id to EntityState(id, "on", buildJsonObject { put("friendly_name", "Kitchen") }))
        )
      val baked =
        kotlin.test.assertNotNull(
          LauncherDesigns.render(
            context,
            config,
            snapshot,
            96,
            96,
            160,
            dark = mode,
            systemTheme = false,
            registry = registry,
            look = look,
          )
        )
      assertTrue(baked.actions.isNotEmpty())
      baked.actions.values.forEach { assertEquals(defaultTapActionFor(id), it) }
    }
  }
}
