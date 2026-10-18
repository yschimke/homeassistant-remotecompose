@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc

import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.profile.RcPlatformProfiles
import ee.schimke.composeai.rcplayer.protocol.*
import ee.schimke.ha.model.*
import ee.schimke.ha.rc.cards.MessageCardConverter
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageInputCaptureTest {
  private val card =
    CardConfig(
      MESSAGE_CARD_TYPE,
      Json.parseToJsonElement(
          """{"type":"custom:terrazzo-message","title":"Send to a room","inputs":[{"key":"room","label":"Contact / Room","type":"choice","value":"Kitchen","options":["Kitchen","Living room"]},{"key":"message","label":"Message","type":"multiline"}],"tap_action":{"action":"perform-action","perform_action":"script.send_room_message","data":{"room":"{{input.room}}","message":"{{input.message}}"}}}"""
        )
        .jsonObject,
    )

  private fun capture(
    mode: CardSizeMode,
    width: Int,
    height: Int,
    profile: androidx.compose.remote.creation.profile.Profile = androidXExperimental,
  ): RcDocument = runBlocking {
    val context = org.robolectric.RuntimeEnvironment.getApplication()
    val bytes =
      captureSingleRemoteDocument(
          context = context,
          creationDisplayInfo =
            RemoteCreationDisplayInfo(width, height, context.resources.configuration.densityDpi),
          profile = profile,
        ) {
          ProvideCardSizeMode(mode) { MessageCardConverter().Render(card, HaSnapshot()) }
        }
        .bytes
    RcDocumentCodec.decode(bytes)
  }

  @Test
  fun interactiveCaptureUsesTypedCustomInputsAndReturnChannels() {
    val document = capture(CardSizeMode.Wrap, 360, 400)
    val controls = document.operations.filterIsInstance<RcCustomLayout>()
    assertEquals(2, controls.size)
    controls.forEach { component ->
      assertEquals(
        RcCustomProperty.TEXT_RETURN,
        component.properties.single { it.type == RcInput.VALUE_RETURN }.dataType,
      )
    }
    assertTrue(document.operations.filterIsInstance<RcTextData>().any { it.text == RcInput.CHOICE })
  }

  @Test
  fun wearAndLauncherCapturesContainAnOpenAppAlternativeWithoutEditors() {
    for ((width, height) in listOf(184 to 44, 160 to 80)) {
      val document = capture(CardSizeMode.Fixed, width, height, RcPlatformProfiles.ANDROIDX)
      assertTrue(document.operations.none { it is RcCustomLayout })
      val text = document.operations.filterIsInstance<RcTextData>().map { it.text }
      assertTrue(text.contains("Open app to send a message"))
      assertTrue(text.any { it.contains("Navigate") && it.contains("/lovelace") })
    }
  }

  @Test
  fun unsupportedStableWriterShowsAnAlternativeInsteadOfThrowing() {
    val document = capture(CardSizeMode.Wrap, 360, 400, RcPlatformProfiles.ANDROIDX)
    assertTrue(document.operations.none { it is RcCustomLayout })
    assertTrue(
      document.operations.filterIsInstance<RcTextData>().any {
        it.text == "Open app to send a message"
      }
    )
  }
}
