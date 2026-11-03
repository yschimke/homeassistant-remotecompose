package ee.schimke.ha.designs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.design.DesignSurface
import ee.schimke.ha.rc.CardDesignTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color

/**
 * The card the design server seeds for a light (`UiBuilderSeed.document` on `HaCardsSeed.card`), as
 * an agent drafts it: collapsible rows in a collapsible column, the name and state beside a switch
 * on `light.s0.is_on`. "Use it" publishes it through this compiler, so it must draw, follow the
 * light's pushed values and tap the card's own toggle.
 */
class SeededCardCompileTest {
  private val compiler = UiBuilderDesignCompiler()

  private val seed: JsonObject =
    Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/seeded-light-card.json")).readText()
      )
      .jsonObject

  private val key =
    DesignKey(
      cardType = "tile",
      surface = DesignSurface.MobileApp,
      rcApiLevel = CardDesignTemplates.MOBILE_APP_API_LEVEL,
      shape = "domain=light",
      theme = "baked:Material3:light",
    )

  @Test
  fun theSeedHasNothingThisCompilerRefuses() {
    assertEquals(emptyList(), compiler.problems(seed, emptySet()))
  }

  @Test
  fun itIsAdaptiveAndBindsTheLightByName() {
    val operations =
      RcDocumentCodec.decode(compiler.compile(seed, emptyMap(), key), RcWireLimits()).operations
    val names = operations.filterIsInstance<RcNamedVariable>().map { it.name }
    for (name in listOf("light.s0.is_on", "light.s0.name", "light.s0.state")) {
      assertTrue("USER:$name" in names, "binds $name: $names")
    }
    assertTrue(operations.any { it is RcCollapsibleRowLayout }, "a collapsible row")
    assertTrue(operations.any { it is RcCollapsibleColumnLayout }, "a collapsible column")
  }

  @Test
  fun theSwitchTogglesTheSlotNotARealEntity() {
    val text = String(compiler.compile(seed, emptyMap(), key), Charsets.ISO_8859_1)
    // The host resolves a slot toggle to the card's own action (CardTemplatePlayback.resolveTap).
    assertTrue(
      Regex("Toggle[^\\n]{0,40}light\\.s0\"").containsMatchIn(text),
      "taps Toggle(light.s0)",
    )
  }

  @Test
  fun theCardFollowsTheLight() {
    val bytes = compiler.compile(seed, emptyMap(), key)
    val on = play(bytes, values("Kitchen", "On", on = true))
    val off = play(bytes, values("Kitchen", "Off", on = false))
    assertTrue(differs(on, off), "the switch and state follow is_on")
  }

  private fun values(name: String, state: String, on: Boolean) =
    mapOf(
      "light.s0.name" to RcNamedValue.Text(name),
      "light.s0.state" to RcNamedValue.Text(state),
      "light.s0.is_on" to RcNamedValue.Integer(if (on) 1 else 0),
    )

  private fun play(bytes: ByteArray, values: Map<String, RcNamedValue>): Bitmap {
    val image =
      ImageComposeScene(360, 120, Density(1f)) {
          Box(Modifier.width(360.dp)) {
            RcComposePlayer(
              bytes,
              namedValues = mutableStateMapOf<String, RcNamedValue>().apply { putAll(values) },
            )
          }
        }
        .use { scene ->
          scene.render()
          scene.render(2_000_000_000L)
        }
    return Bitmap.makeFromImage(image)
  }

  private fun differs(a: Bitmap, b: Bitmap): Boolean {
    for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
      if (Color.getR(a.getColor(x, y)) != Color.getR(b.getColor(x, y))) return true
    }
    return false
  }
}
