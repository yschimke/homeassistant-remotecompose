package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RcSafeModeTest {
  @Test
  fun aDocumentThePlayerCanRunPasses() {
    val bytes =
      RcDocumentCodec.encode(
        RcDocument(
          RcHeader(RcVersion(1, 0, 0)),
          listOf(RcNamedVariable(42, RcNamedVariable.STRING_TYPE, "USER:light.s0.state")),
        )
      )
    assertNull(RcSafeMode.check(bytes))
    // Checked with the live values the host will play it with.
    assertNull(RcSafeMode.check(bytes, mapOf("light.s0.state" to RcNamedValue.Text("On"))))
  }

  @Test
  fun bytesThatAreNotADocumentFail() {
    assertNotNull(RcSafeMode.check(byteArrayOf(1, 2, 3)))
  }
}
