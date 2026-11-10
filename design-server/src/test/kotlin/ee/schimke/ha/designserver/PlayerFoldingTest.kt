package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerFoldingTest {
  /** A `RemoteDensity.Host` heading card: its title's padding reads a variable over density. */
  private val heading =
    checkNotNull(javaClass.getResource("/player-folding/heading.rc")).readBytes()

  @Test
  fun foldsTheDisplayIntoLiterals() {
    val folded =
      assertNotNull(
        PlayerFolding.fold(heading, PlayerFolding.Player(), bound = setOf("card.title")),
      )
    val before = RcDocumentCodec.decode(heading, RcWireLimits()).operations
    val after = RcDocumentCodec.decode(folded.bytes, RcWireLimits()).operations
    // The density / font-size expressions are gone: nothing is left for the player to evaluate.
    assertTrue(before.any { it is RcFloatExpression }, "fixture has expressions: $before")
    assertTrue(after.none { it is RcFloatExpression }, "still has expressions: $after")
    // The title is a TextLayout drawing its colour as a literal: the light theme's on-surface.
    val text = after.filterIsInstance<RcTextLayout>().single()
    assertEquals(0, text.flags and RcTextLayout.FLAG_DYNAMIC_COLOR, "colour still a reference")
    assertEquals(0xFF1D1B20.toInt(), text.color)
    // The title is still the host's to push.
    assertTrue(after.any { it is RcNamedVariable && it.name.endsWith("card.title") }, "$after")
    System.getenv("PLAYER_FOLDING_OUT")?.let { File(it).writeBytes(folded.bytes) }
  }

  @Test
  fun aWholeScreenPlayerGetsItsSizeAsTheCanvas() {
    val folded =
      assertNotNull(
        PlayerFolding.fold(
          heading,
          PlayerFolding.Player(width = 800, height = 480),
          bound = setOf("card.title"),
        ),
      )
    val header = RcDocumentCodec.decode(folded.bytes, RcWireLimits()).header
    assertEquals(800, header.width)
    assertEquals(480, header.height)
    System.getenv("PLAYER_FOLDING_SIZED_OUT")?.let { File(it).writeBytes(folded.bytes) }
  }

  @Test
  fun servesTheFoldedBlobBySha() {
    val folded = assertNotNull(PlayerFolding.forPlayer("heading", heading, PlayerFolding.Player()))
    assertContentEquals(folded.bytes, PlayerFolding.blob(folded.sha256))
    // Once per design and player.
    assertTrue(PlayerFolding.forPlayer("heading", heading, PlayerFolding.Player()) === folded)
  }
}
