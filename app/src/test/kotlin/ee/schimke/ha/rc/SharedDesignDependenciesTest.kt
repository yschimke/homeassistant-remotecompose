package ee.schimke.ha.rc

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedDesignDependenciesTest {
  private fun template(vararg names: String): ByteArray =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 0, 0)),
        names.mapIndexed { index, name ->
          RcNamedVariable(100 + index, RcNamedVariable.FLOAT_TYPE, name)
        },
      ),
    )

  @Test
  fun selectedDocumentsDetermineOnlyTheirKnownSourcesAndAttributes() {
    val first = template("HA:sun.sun.elevation", "USER:cover.s0.is_on", "HA:lock.front_door.state")
    val second = template("HA:sun.sun.next_rising.minute_of_day")
    assertEquals(mapOf("sun.sun" to setOf("elevation")), sharedDesignDependencies(listOf(first)))
    assertEquals(
      mapOf("sun.sun" to setOf("elevation", "next_rising")),
      sharedDesignDependencies(listOf(first, second)),
    )
    assertEquals(emptyMap(), sharedDesignDependencies(listOf(template("USER:light.s0.state"))))
  }
}
