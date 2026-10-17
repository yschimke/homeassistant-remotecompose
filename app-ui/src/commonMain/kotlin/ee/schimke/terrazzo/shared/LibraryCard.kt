package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.design.CardDesignSlots
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Values stay in the host. Resolution sends only card type and anonymous domain slots. */
@Composable
internal fun LibraryCard(
  card: CardConfig,
  snapshot: HaSnapshot,
  style: String,
  dark: Boolean,
  fallback: @Composable () -> Unit,
) {
  var bytes by remember(card, style, dark) { mutableStateOf<ByteArray?>(null) }
  var failed by remember(card, style, dark) { mutableStateOf(false) }
  LaunchedEffect(card, style, dark) {
    val http = HttpClient()
    try {
      val slots = CardDesignSlots.slotMap(card).values.toList()
      val anonymous = buildJsonObject {
        put("type", card.type)
        if (card.raw["entity"] != null && slots.isNotEmpty()) put("entity", slots.first())
        else if (slots.isNotEmpty()) put("entities", JsonArray(slots.map(::JsonPrimitive)))
      }
      val response =
        http.post("https://ha.coo.ee/api/cards/resolve") {
          contentType(ContentType.Text.Plain)
          setBody(
            buildJsonObject {
              put("card", anonymous)
              put("cardStyle", style)
              put("dark", dark)
            }
              .toString()
          )
        }
      check(response.status.isSuccess())
      val sha =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["sha256"]!!.jsonPrimitive.content
      check(Regex("[0-9a-f]{64}").matches(sha))
      val blob = http.get("https://ha.coo.ee/designs/v1/blobs/$sha.rc")
      check(blob.status.isSuccess())
      val loaded = blob.bodyAsBytes()
      check(Sha256.hex(loaded) == sha)
      check(canPlayWithCmp(loaded))
      bytes = loaded
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failed = true
    } finally {
      http.close()
    }
  }
  val current = bytes
  if (current == null) {
    fallback()
    if (failed) Text("Design unavailable · showing built-in card")
  } else {
    val slots = CardDesignSlots.slotBindings(card, snapshot)
    val values =
      buildMap<String, RcNamedValue> {
        slots.strings.forEach { (k, v) -> put(k, RcNamedValue.Text(v)) }
        slots.booleans.forEach { (k, v) -> put(k, RcNamedValue.Integer(if (v) 1 else 0)) }
        slots.ints.forEach { (k, v) -> put(k, RcNamedValue.Integer(v)) }
        slots.floats.forEach { (k, v) -> put(k, RcNamedValue.FloatValue(v)) }
      }
    RemoteComposeCard(current, Modifier.fillMaxWidth(), values)
  }
}
