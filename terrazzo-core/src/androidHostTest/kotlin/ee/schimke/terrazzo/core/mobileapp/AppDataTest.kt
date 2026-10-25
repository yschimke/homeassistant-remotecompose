package ee.schimke.terrazzo.core.mobileapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AppDataTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun withoutARelayOnlyTheWebsocketChannelIsAsked() {
    assertEquals(
      """{"push_websocket_channel":true}""",
      json.encodeToString(AppData.serializer(), AppData.of(null)),
    )
  }

  @Test
  fun aRelayAddsThePushUrlAndTokenPair() {
    assertEquals(
      """{"push_websocket_channel":true,"push_url":"https://relay.test/haPush","push_token":"t1"}""",
      json.encodeToString(
        AppData.serializer(),
        AppData.of(PushTarget("https://relay.test/haPush", "t1")),
      ),
    )
  }
}
