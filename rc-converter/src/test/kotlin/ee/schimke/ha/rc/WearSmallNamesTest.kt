package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.EntityState
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.cards.defaultRegistry
import kotlin.test.Test
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
 * A single-entity card names its entity in a Wear Small container
 * (`docs/architecture/card-information-hierarchy.md`): an icon alone leaves two buttons, or two
 * lights, on one watch looking the same. The button's name sits beside its icon and keeps its live
 * `<id>.is_on` / `<id>.state` bindings; the light's name takes its dial's mode-chip line.
 */
@Suppress("RestrictedApi")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearSmallNamesTest {

  private val registry = defaultRegistry()

  private val snapshot =
    HaSnapshot(
      states =
        mapOf(
          "light.kitchen" to
            EntityState(
              "light.kitchen",
              "on",
              JsonObject(mapOf("friendly_name" to JsonPrimitive("Kitchen light"))),
            )
        )
    )

  private fun card(type: String, vararg extra: Pair<String, Any>) =
    CardConfig(
      type = type,
      raw =
        buildJsonObject {
          put("type", type)
          put("entity", "light.kitchen")
          extra.forEach { (key, value) ->
            when (value) {
              is Boolean -> put(key, value)
              else -> put(key, value.toString())
            }
          }
        },
    )

  /** [card] in a Wear slot's [heightDp] content box: both Wear tiers are recorded. */
  private fun wearCapture(card: CardConfig, heightDp: Int = 44): ByteArray = runBlocking {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val density = context.resources.displayMetrics.density
    captureSingleRemoteDocument(
        context = context,
        creationDisplayInfo =
          RemoteCreationDisplayInfo(
            (184 * density).toInt(),
            (heightDp * density).toInt(),
            context.resources.configuration.densityDpi,
          ),
        profile = RcPlatformProfiles.ANDROIDX,
      ) {
        ProvideCardSizeMode(CardSizeMode.Fixed) {
          ProvideFixedCanvasAxis(BreakpointAxis.Height) {
            registry.get(card.type)!!.Render(card, snapshot)
          }
        }
      }
      .bytes
  }

  private fun names(bytes: ByteArray): List<String> =
    RcDocumentCodec.decode(bytes, RcWireLimits()).namedVariables.map {
      it.name.removePrefix("USER:")
    }

  private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

  /**
   * Both Wear tiers are recorded and their text shares one string, so this checks the name and its
   * live look, not which tier draws it: `WearButtonSmall` in `wear/` shows the Small one.
   */
  @Test
  fun wearButtonNamesItsEntity() {
    val bytes = wearCapture(card("button"))
    assertTrue("Kitchen light" in text(bytes), "the friendly name is drawn")
    assertTrue("light.kitchen.is_on" in names(bytes), "the accent stays live: ${names(bytes)}")
  }

  @Test
  fun wearButtonPrefersTheCardName() {
    assertTrue("Pantry" in text(wearCapture(card("button", "name" to "Pantry"))))
  }

  @Test
  fun wearButtonStateIsTheLiveBinding() {
    val names = names(wearCapture(card("button", "show_state" to true)))
    assertTrue("light.kitchen.state" in names, "show_state binds the host's state: $names")
  }

  @Test
  fun wearLightNamesItsEntity() {
    assertTrue("Kitchen light" in text(wearCapture(card("light"))), "the light's name is drawn")
    assertTrue("Lounge" in text(wearCapture(card("light", "name" to "Lounge"))))
  }
}
