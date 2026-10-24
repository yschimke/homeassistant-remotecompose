package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The button card honours HA's `show_state` and `show_icon` (`hui-button-card.ts`: `show_icon` and
 * `show_name` default true, `show_state` false), and a shown state is the live `<id>.state`
 * binding, so it follows the entity without a re-encode (live-card-updates.md, path 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp-mdpi")
class ButtonCardOptionsTest {

  private val registry = defaultRegistry()

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "lock.front_door" to
            EntityState(
              "lock.front_door",
              "locked",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Front door"))),
            )
        )
    )

  private fun button(vararg options: Pair<String, Boolean>) =
    CardConfig(
      type = "button",
      raw =
        buildJsonObject {
          put("type", "button")
          put("entity", "lock.front_door")
          options.forEach { (key, value) -> put(key, value) }
        },
    )

  private fun capture(card: CardConfig): ByteArray = runBlocking {
    captureCardDocument(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        widthPx = 200,
        heightPx = 100,
        registry = registry,
        card = card,
        snapshot = snapshot,
        forceRefresh = true,
      )
      .bytes
  }

  private fun names(bytes: ByteArray): List<String> =
    RcDocumentCodec.decode(bytes, RcWireLimits()).namedVariables.map {
      it.name.removePrefix("USER:")
    }

  @Test
  fun showStateBindsTheLiveState() {
    val names = names(capture(button("show_state" to true)))
    assertTrue("lock.front_door.state" in names, "the state line is the host's binding: $names")
  }

  @Test
  fun stateIsHiddenByDefault() {
    val names = names(capture(button()))
    assertFalse("lock.front_door.state" in names, "HA's show_state defaults false: $names")
    assertFalse(
      "lock.front_door.state" in names(capture(button("show_state" to false))),
      "show_state: false",
    )
  }

  @Test
  fun showIconFalseDropsTheIcon() {
    val withIcon = capture(button("show_state" to true))
    val withoutIcon = capture(button("show_state" to true, "show_icon" to false))
    assertTrue(
      withoutIcon.size < withIcon.size,
      "no icon chip is drawn: ${withoutIcon.size} vs ${withIcon.size} bytes",
    )
  }
}
