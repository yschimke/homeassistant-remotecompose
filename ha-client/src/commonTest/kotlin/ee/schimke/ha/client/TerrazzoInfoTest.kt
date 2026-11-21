package ee.schimke.ha.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `terrazzo/info`'s answer, and how [detectTerrazzo] tells an older integration from none. */
class TerrazzoInfoTest {
  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  private val current =
    obj(
      """{"protocol":1,"min_client":1,"version":"0.1.98","capabilities":{
        "edit":1,"push":1,"design_selections":1,
        "design_server":{"available":true,"version":"0.4.2"},
        "web_origin":1,"agent":1,"mcp":{"enabled":true},"wall":"v1"}}""",
    )

  private fun refusal(command: String, code: String) = HaCommandException(command, code, code)

  private suspend fun detect(
    info: suspend () -> JsonObject,
    manifest: suspend () -> JsonObject = { error("manifest/get is only for an older integration") },
    clientProtocol: Int = 1,
  ) = detectTerrazzo(clientProtocol, info, manifest)

  @Test
  fun aCurrentIntegrationIsInstalledWithItsCapabilities() = runTest {
    val installed = assertIs<TerrazzoIntegration.Installed>(detect({ current }))
    val info = installed.info
    assertEquals(1, info.protocol)
    assertEquals("0.1.98", info.version)
    assertEquals(1, info.capabilities.edit)
    assertEquals(1, info.capabilities.designSelections)
    assertEquals(TerrazzoDesignServerCapability(true, "0.4.2"), info.capabilities.designServer)
    assertEquals(true, info.capabilities.mcp?.enabled)
    assertEquals("v1", info.capabilities.wall)
    // Not offered to this user: an administrator's.
    assertNull(info.capabilities.editApply)
    assertNull(info.capabilities.designServerOpen)
  }

  @Test
  fun unknownKeysAndCapabilitiesAreIgnoredAndMissingOnesAreUnsupported() = runTest {
    val info =
      terrazzoInfoOf(
        obj(
          """{"protocol":1,"min_client":1,"version":"9.0.0","future":{"x":1},"capabilities":{
            "edit":3,"teleport":{"enabled":true},"design_server":{"available":false,"version":null,
            "region":"eu"},"hologram":2}}""",
        ),
      )
    assertEquals(3, info.capabilities.edit)
    assertEquals(TerrazzoDesignServerCapability(false, null), info.capabilities.designServer)
    assertNull(info.capabilities.push)
    assertNull(info.capabilities.mcp)
    assertNull(info.capabilities.wall)
  }

  @Test
  fun anIntegrationOlderThanTheCommandIsTooOldAtItsManifestVersion() = runTest {
    val sent = mutableListOf<String>()
    val result =
      detect(
        info = {
          sent += "terrazzo/info"
          throw refusal("terrazzo/info", "unknown_command")
        },
        manifest = {
          sent += "manifest/get"
          obj("""{"domain":"terrazzo","name":"Terrazzo Remote Compose","version":"0.1.90"}""")
        },
      )
    assertEquals(TerrazzoIntegration.TooOld("0.1.90"), result)
    assertEquals(listOf("terrazzo/info", "manifest/get"), sent)
  }

  @Test
  fun withoutTheIntegrationItIsAbsent() = runTest {
    val result =
      detect(
        info = { throw refusal("terrazzo/info", "unknown_command") },
        manifest = { throw refusal("manifest/get", "not_found") },
      )
    assertEquals(TerrazzoIntegration.Absent, result)
  }

  @Test
  fun anIntegrationThatNeedsANewerClientIsUnsupported() = runTest {
    val newer = obj("""{"protocol":3,"min_client":2,"version":"2.0.0","capabilities":{}}""")
    assertEquals(TerrazzoIntegration.Unsupported(2), detect({ newer }))
    assertIs<TerrazzoIntegration.Installed>(detect({ newer }, clientProtocol = 2))
  }

  @Test
  fun otherRefusalsAreNotMistakenForAMissingIntegration() = runTest {
    val refused =
      assertFailsWith<HaCommandException> {
        detect(info = { throw refusal("terrazzo/info", "unauthorized") })
      }
    assertEquals("unauthorized", refused.code)
  }
}
