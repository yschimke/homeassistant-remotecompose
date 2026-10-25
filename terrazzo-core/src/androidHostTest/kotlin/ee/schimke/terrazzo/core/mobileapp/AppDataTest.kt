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

  /** The webhook envelope exactly as HA's `WEBHOOK_PAYLOAD_SCHEMA` takes an encrypted call. */
  @Test
  fun theEncryptedWebhookAlwaysSaysItIsEncrypted() {
    assertEquals(
      """{"type":"update_registration","encrypted":true,"encrypted_data":"c2VhbGVk"}""",
      json.encodeToString(
        EncryptedWebhook.serializer(),
        EncryptedWebhook("update_registration", encrypted = true, encryptedData = "c2VhbGVk"),
      ),
    )
  }
}
