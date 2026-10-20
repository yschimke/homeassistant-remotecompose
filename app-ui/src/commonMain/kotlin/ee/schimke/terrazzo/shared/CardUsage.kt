package ee.schimke.terrazzo.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.ha.client.design.Sha256
import ee.schimke.ha.client.design.TemplateUsage
import ee.schimke.ha.client.design.UsageKey
import ee.schimke.ha.client.design.UsageSharing
import ee.schimke.ha.client.design.UsageSource
import ee.schimke.ha.client.design.UsageStore
import ee.schimke.ha.client.design.UsageUserAgent
import ee.schimke.ha.client.design.uploadWithDefaultClient
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.model.CardConfig
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Where a desktop or web build keeps its usage ledger and its "Share anonymous card usage" choice,
 * and what it reports itself as. The Android app counts in its own `CardUsage`, so it has none.
 */
internal class PlatformCardUsage(
  val store: UsageStore,
  /** [ee.schimke.ha.client.design.UsageSurfaces] tag. */
  val surface: String,
  /** The User-Agent platform tag: lowercase letters and digits. */
  val platform: String,
  /** False in a browser, which won't send a page's own User-Agent. */
  val sendUserAgentHeader: Boolean,
  /** The stored [UsageSharing] name, or null before the user ever chose. */
  val readSharing: () -> String?,
  val writeSharing: (String) -> Unit,
)

internal expect fun platformCardUsage(): PlatformCardUsage?

/**
 * The desktop and web app's anonymous card usage (docs/followups/template-usage-tracking.md), with
 * the phone's rules: opt-in through the [UsageSharing] tri-state, nothing counted or sent while it
 * is effectively off (and what was pending deleted), a card counted once a day under a local
 * fingerprint, finished days uploaded bucketed to the configured design server.
 */
internal object AppCardUsage {
  private val platform: PlatformCardUsage? = platformCardUsage()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val choice = MutableStateFlow(UsageSharing.parse(platform?.readSharing?.invoke()))

  private val usage: TemplateUsage? = platform?.let { p ->
    TemplateUsage(
      store = p.store,
      sharing = { choice.value.effective() },
      today = { TemplateUsage.utcDay(Clock.System.now().toEpochMilliseconds()) },
    )
  }

  init {
    if (!choice.value.effective()) scope.launch { usage?.clear() }
  }

  /** Whether this host counts usage at all. */
  val supported: Boolean
    get() = platform != null

  /** The stored choice; the settings switch shows its [UsageSharing.effective] value. */
  val sharing: StateFlow<UsageSharing> = choice

  /** What this app says about itself to the design server: its release, nothing else. */
  val userAgent: UsageUserAgent?
    get() = platform?.let {
      UsageUserAgent(
        version = FeedbackBuildInfo.VERSION,
        platform = it.platform,
        surface = it.surface,
      )
    }

  /** Stores the switch's explicit choice; turning it off deletes what was pending. */
  fun choose(on: Boolean) {
    val p = platform ?: return
    val chosen = UsageSharing.chosen(on)
    p.writeSharing(chosen.name)
    choice.value = chosen
    if (!chosen.effective()) scope.launch { usage?.clear() }
  }

  /**
   * Notes that [card] was drawn in card style [style] from [source]. The card is reduced to a local
   * fingerprint that never leaves the device. A no-op where unsupported or while sharing is off.
   */
  fun record(card: CardConfig, style: String, source: UsageSource) {
    val p = platform ?: return
    val usage = usage ?: return
    if (!choice.value.effective()) return
    val fingerprint = Sha256.hex(card.raw.toString().encodeToByteArray())
    scope.launch {
      usage.record(
        UsageKey(card.type, DesignKey.DEFAULT_VARIANT, style, source, p.surface),
        fingerprint,
      )
    }
  }

  /**
   * Uploads finished days to [server]'s usage endpoint. Nothing without a design server, and
   * nothing (pending cleared) while sharing is off.
   */
  suspend fun upload(server: String): Int {
    val p = platform ?: return 0
    val usage = usage ?: return 0
    val agent = userAgent ?: return 0
    if (server.isBlank()) return 0
    return runCatching {
        usage.uploadWithDefaultClient(cardDesignApiBase(server), agent, p.sendUserAgentHeader)
      }
      .getOrDefault(0)
  }
}

/**
 * "Share anonymous card usage", as on the phone: off until the user turns it on, saying what is
 * sent and linking to the privacy policy. Shown only where the host counts usage and a design
 * server is set.
 */
@Composable
internal fun CardUsageSetting(designServer: String) {
  if (!AppCardUsage.supported || designServer.isBlank()) return
  val sharing by AppCardUsage.sharing.collectAsState()
  val uriHandler = LocalUriHandler.current
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f).padding(end = 16.dp)) {
        Text("Share anonymous card usage", style = MaterialTheme.typography.titleMedium)
        Text(
          "Once a day, send the design server which card designs drew your cards (card type, " +
            "design and style, in rough counts). Never entities, names, states or addresses.",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Switch(
        checked = sharing.effective(),
        onCheckedChange = AppCardUsage::choose,
        modifier = Modifier.semantics { contentDescription = "Share anonymous card usage" },
      )
    }
    TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
  }
}

/** The published privacy policy (`docs/privacy.md`). */
internal const val PRIVACY_POLICY_URL: String =
  "https://yschimke.github.io/homeassistant-remotecompose/privacy/"
