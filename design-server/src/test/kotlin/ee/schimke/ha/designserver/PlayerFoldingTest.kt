package ee.schimke.ha.designserver

import ee.schimke.composeai.rcplayer.edit.RcPlayerFeature
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.ha.designserver.PlayerFolding.Display
import ee.schimke.ha.designserver.PlayerFolding.Player
import ee.schimke.ha.designserver.PlayerFolding.Profile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PlayerFoldingTest {
  /** A `RemoteDensity.Host` heading card: its title's padding reads a variable over density. */
  private val heading =
    checkNotNull(javaClass.getResource("/player-folding/heading.rc")).readBytes()

  private fun operations(bytes: ByteArray) =
    RcDocumentCodec.decode(bytes, RcWireLimits()).operations

  @Test
  fun kobentoKeepsCoreTextAndGetsLiteralPaddings() {
    val folded = assertNotNull(PlayerFolding.fold(heading, Player(), bound = setOf("card.title")))
    val before = operations(heading)
    val after = operations(folded.bytes)
    assertTrue(folded.notes.isEmpty(), folded.notes.toString())
    // The density / font-size expressions are gone: nothing is left for the player to evaluate.
    assertTrue(before.any { it is RcFloatExpression }, "fixture has expressions: $before")
    assertTrue(after.none { it is RcFloatExpression }, "still has expressions: $after")
    // rccore decodes CoreText, so it stays; its padding is a literal, which rccore can lay out.
    assertTrue(after.any { it is RcCoreText }, "$after")
    val padding = after.filterIsInstance<RcPaddingModifier>().single()
    assertTrue(
      listOf(padding.left, padding.top, padding.right, padding.bottom).none { it.isNaNEncoded },
      "$padding",
    )
    assertEquals(8f, padding.top.value)
    // The title is still the host's to push.
    assertTrue(after.any { it is RcNamedVariable && it.name.endsWith("card.title") }, "$after")
    System.getenv("PLAYER_FOLDING_OUT")?.let { File(it).writeBytes(folded.bytes) }
  }

  @Test
  fun aPlayerWithoutCoreTextGetsTextLayoutWithItsColour() {
    val player =
      Player(
        profile = Profile.KOBENTO_MCU.without(listOf("op:CORE_TEXT")),
      )
    val folded = assertNotNull(PlayerFolding.fold(heading, player, bound = setOf("card.title")))
    val after = operations(folded.bytes)
    assertTrue(after.none { it is RcCoreText }, "$after")
    // Without dynamic text colours, the title draws its colour as a literal: on-surface.
    val text = after.filterIsInstance<RcTextLayout>().single()
    assertEquals(0, text.flags and RcTextLayout.FLAG_DYNAMIC_COLOR, "colour still a reference")
    assertEquals(0xFF1D1B20.toInt(), text.color)
  }

  @Test
  fun aWholeScreenPlayerGetsItsSizeAsTheCanvas() {
    val player = Player(Display(width = 800, height = 480))
    val folded = assertNotNull(PlayerFolding.fold(heading, player, bound = setOf("card.title")))
    val header = RcDocumentCodec.decode(folded.bytes, RcWireLimits()).header
    assertEquals(800, header.width)
    assertEquals(480, header.height)
    System.getenv("PLAYER_FOLDING_SIZED_OUT")?.let { File(it).writeBytes(folded.bytes) }
  }

  private fun player(json: String) =
    PlayerFolding.parsePlayer(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun aPlayerIsADisplayAndAProfile() {
    assertEquals(
      Player(Display(1f, 1.3f, 800, 480), Profile.KOBENTO_MCU),
      player(
        """{"display": {"density": 1, "fontScale": 1.3, "width": 800, "height": 480},
            "profile": "kobento-mcu"}""",
      ),
    )
    // The first form: a flat display, for the small player the wall API was made for.
    assertEquals(
      Player(Display(width = 800, height = 480), Profile.KOBENTO_MCU),
      player("""{"density": 1, "fontScale": 1, "width": 800, "height": 480}"""),
    )
    assertEquals(Profile("widgets-v6"), player("""{"profile": "widgets-v6"}""").profile)
    val custom =
      player(
        """{"profile": {"base": "androidx",
            "without": ["op:CORE_TEXT", "op:208", "feature:variable-float-fields"]}}""",
      )
    assertEquals(
      Profile(
        "androidx",
        withoutOpcodes = setOf(RcOpcodes.CORE_TEXT, RcOpcodes.LAYOUT_TEXT),
        withoutFeatures = setOf(RcPlayerFeature.VARIABLE_FLOAT_FIELDS),
      ),
      custom.profile,
    )
    assertTrue(RcOpcodes.CORE_TEXT !in custom.profile.target.supportedOpcodes)
    assertEquals(
      setOf(RcPlayerFeature.VARIABLE_FLOAT_FIELDS),
      custom.profile.target.missingFeatures,
    )
    // A named family as a base keeps what it already lacks.
    assertEquals(
      Profile.KOBENTO_MCU.withoutFeatures,
      player("""{"profile": {"base": "kobento-mcu", "without": ["op:CORE_TEXT"]}}""")
        .profile
        .withoutFeatures,
    )
  }

  @Test
  fun aProfileItCantReadIsAnError() {
    for (bad in
      listOf(
        """{"profile": "nokia"}""",
        """{"profile": {"base": "nokia"}}""",
        """{"profile": {"without": ["op:NOT_AN_OP"]}}""",
        """{"profile": {"without": ["feature:telepathy"]}}""",
        """{"profile": {"without": ["CORE_TEXT"]}}""",
        """{"profile": {"without": "op:CORE_TEXT"}}""",
        """{"width": 800}""",
      )) {
      assertFailsWith<IllegalArgumentException>(bad) { player(bad) }
    }
  }

  @Test
  fun servesTheFoldedBlobBySha() {
    val folded = assertNotNull(PlayerFolding.forPlayer("heading", heading, Player()))
    assertContentEquals(folded.bytes, PlayerFolding.blob(folded.sha256))
    // Once per design and player.
    assertTrue(PlayerFolding.forPlayer("heading", heading, Player()) === folded)
  }

  @Test
  fun aDesignStillResolvedKeepsItsBlob() {
    // One design resolved again and again while many others are folded: its blob must still be
    // there to fetch, however long ago a player last downloaded it.
    val hot = Player(Display(width = 800, height = 480))
    val first = assertNotNull(PlayerFolding.forPlayer("hot", heading, hot))
    for (width in 1..600) {
      assertNotNull(
        PlayerFolding.forPlayer("other", heading, Player(Display(width = width, height = 480))),
      )
      assertNotNull(PlayerFolding.forPlayer("hot", heading, hot))
    }
    assertContentEquals(first.bytes, PlayerFolding.blob(first.sha256))
  }
}
