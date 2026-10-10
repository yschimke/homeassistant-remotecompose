package ee.schimke.terrazzo.shared

import ee.schimke.composeai.rcplayer.protocol.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteComposeCompatibilityTest {
  private fun bytes(encoding: Int): ByteArray =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        listOf(
          RcBitmapData(42, 1, 1, RcBitmapData.TYPE_RAW8888, encoding, byteArrayOf(0, 0, 0, -1))
        ),
      )
    )

  @Test
  fun inlineBitmapsUseCmpWhileExternalBitmapsRetainTheHostLoader() {
    assertTrue(canPlayWithCmp(bytes(RcBitmapData.ENCODING_INLINE)))
    assertFalse(canPlayWithCmp(bytes(1)))
  }

  @Test
  fun invalidDocumentsDoNotEnterThePlayer() {
    assertFalse(canPlayWithCmp(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun connectionFormRejectsEmptyAndNonHttpAddresses() {
    assertTrue(validServerUrl("https://home.example.com"))
    assertTrue(validServerUrl("http://192.168.1.5:8123"))
    assertFalse(validServerUrl("https://"))
    assertFalse(validServerUrl("file:///tmp/home"))
    assertFalse(validServerUrl("https://bad host"))
  }
}
