package ee.schimke.ha.rc.components

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxSize
import ee.schimke.ha.rc.androidX
import ee.schimke.ha.rc.captureRemoteDocument
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ProvideNamedHaTheme] carries an HA palette as Remote Material 3's named `WearM3.<role>` colours,
 * which is what lets the preview server re-theme a published sticker. This pins that the names
 * reach the document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NamedHaThemeTest {

  @Test
  fun namedTheme_writesHaRolesAsNamedWearM3Colors() {
    val haTheme = HaTheme.Light.asNamedRemote()
    val bytes = runBlocking {
      captureRemoteDocument(RemoteCreationDisplayInfo(100, 100, 160), androidX) {
        RemoteBox(RemoteModifier.fillMaxSize().background(haTheme.cardBackground)) {
          RemoteBox(RemoteModifier.fillMaxSize().background(haTheme.primaryText))
        }
      }
    }
    val document =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(ByteArrayInputStream(bytes)))
      }
    val names = requireNotNull(document.namedColors).filterNotNull()

    for (role in listOf("WearM3.surfaceContainerHigh", "WearM3.onSurface")) {
      assertTrue(names.any { it.endsWith(role) }, "Expected $role in $names")
    }
  }
}
