package ee.schimke.terrazzo.wear.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rcplayer.edit.namedVariables
import ee.schimke.composeai.rcplayer.protocol.RcColorConstant
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.rc.androidX
import ee.schimke.ha.rc.captureSingleRemoteDocument
import ee.schimke.ha.rc.components.RemoteHaTheme
import ee.schimke.ha.rc.components.ThemeStyle
import ee.schimke.terrazzo.wear.ui.terrazzoWearColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A Wear widget's Wear Material 3 colours are the named `WearM3.<role>` states its cards draw with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class WearHaThemeTest {
  private val scheme = terrazzoWearColorScheme(ThemeStyle.entries.first())

  @Test
  fun theSchemesColors_areTheDefaultsOfTheHaRoles() {
    val theme = WearWidgetColors(scheme.copy(surfaceContainerHigh = Color.Magenta)).asHaTheme()
    val document = documentDrawing(theme) { it.cardBackground }

    assertEquals(Color.Magenta.toArgb(), document.namedColor("WearM3.surfaceContainerHigh"))
  }

  @Test
  fun aHaRole_isWrittenAsItsNamedWearMaterialColor() {
    val theme = WearWidgetColors(scheme).asHaTheme()
    val document = documentDrawing(theme) { it.sectionBackground }

    assertEquals(scheme.surfaceContainer.toArgb(), document.namedColor("WearM3.surfaceContainer"))
  }

  /** A document whose only content is a box filled with [role] of [theme]. */
  private fun documentDrawing(
    theme: RemoteHaTheme,
    role: (RemoteHaTheme) -> ee.schimke.composeai.rccreation.compose.state.RemoteColor,
  ): RcDocument = runBlocking {
    val bytes =
      captureSingleRemoteDocument(
          context = RuntimeEnvironment.getApplication(),
          creationDisplayInfo = RemoteCreationDisplayInfo(100, 100, 160),
          profile = androidX,
        ) {
          RemoteBox(RemoteModifier.size(10.rdp).background(role(theme)))
        }
        .bytes
    RcDocumentCodec.decode(bytes, RcWireLimits())
  }

  /** The default the document gives the colour a host binds as [name]. */
  private fun RcDocument.namedColor(name: String): Int {
    val variable =
      assertNotNull(
        namedVariables.firstOrNull { it.name.endsWith(name) },
        "Expected $name in ${namedVariables.map { it.name }}",
      )
    return operations.filterIsInstance<RcColorConstant>().first { it.id == variable.id }.argb
  }
}
