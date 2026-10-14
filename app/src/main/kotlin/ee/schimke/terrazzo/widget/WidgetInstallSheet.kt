@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.pin.MobilePinnedCard
import ee.schimke.terrazzo.core.pin.PinStore
import ee.schimke.terrazzo.core.widget.WidgetStore
import ee.schimke.terrazzo.dashboard.toPinnedData
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Review a local card edit, try adaptive sizes, then hand its live preview to the launcher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetInstallSheet(
  baseUrl: String,
  dashboardUrlPath: String,
  card: CardConfig,
  snapshot: HaSnapshot,
  onDismiss: () -> Unit,
  onMonitor: (() -> Unit)? = null,
  refreshSnapshot: (suspend () -> HaSnapshot)? = null,
) {
  val context = LocalContext.current
  val installer = remember { WidgetInstaller(context.applicationContext) }
  val store = LocalTerrazzoGraph.current.widgetStore
  val pinStore = LocalTerrazzoGraph.current.pinStore
  val pinScope = rememberCoroutineScope()
  var name by remember(card) { mutableStateOf(card.raw["name"]?.jsonPrimitive?.content ?: "") }
  val editedCard =
    remember(card, name) {
      if (name.isBlank() || name == (card.raw["name"]?.jsonPrimitive?.content ?: "")) card
      else
        card.copy(
          raw = kotlinx.serialization.json.JsonObject(card.raw + ("name" to JsonPrimitive(name)))
        )
    }
  var renderedPreview by remember(editedCard) { mutableStateOf<android.widget.RemoteViews?>(null) }
  var liveSnapshot by remember(card) { mutableStateOf(snapshot) }
  LaunchedEffect(snapshot) { liveSnapshot = snapshot }
  val latestRefresh by rememberUpdatedState(refreshSnapshot)
  LaunchedEffect(card, baseUrl, refreshSnapshot != null) {
    if (latestRefresh != null) {
      while (isActive) {
        runCatching { latestRefresh!!.invoke() }.onSuccess { liveSnapshot = it }
        delay(5_000)
      }
    }
  }
  var installError by remember { mutableStateOf<String?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val installed by store.installed.collectAsState(initial = emptyList())
  val installedCount = installed.size
  var requestedIds by remember { mutableStateOf<Set<Int>?>(null) }
  LaunchedEffect(installed) {
    val previous = requestedIds
    if (
      previous != null &&
        installed.any { it.widgetId !in previous && it.baseUrl == baseUrl && it.card == editedCard }
    )
      onDismiss()
  }
  LaunchedEffect(Unit) {
    // Heal rows for widgets the user has since removed from the
    // launcher before trusting the count, so the "slots free" line
    // and the cap reflect what's actually installed.
    WidgetSync.reconcile(context.applicationContext)
  }

  val capHit = installedCount >= WidgetStore.MAX_WIDGETS
  val pinSupported = remember { installer.isSupported() }

  val cardData = remember(editedCard) { editedCard.toPinnedData() }
  val pinKey =
    remember(baseUrl, dashboardUrlPath, cardData.rawJson) {
      PinStore.cardKey(baseUrl, dashboardUrlPath, cardData.rawJson)
    }
  val isCardPinned by pinStore.isCardPinned(pinKey).collectAsState(initial = false)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
      Column(
        modifier =
          Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text("Make it yours", style = MaterialTheme.typography.headlineSmall)
        Text(
          "This widget will keep itself up to date. ${WidgetStore.MAX_WIDGETS - installedCount} of " +
            "${WidgetStore.MAX_WIDGETS} slots free.",
          style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Widget name") },
          placeholder = { Text("Keep the original name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        WidgetPreparationPreview(baseUrl, editedCard, liveSnapshot) { renderedPreview = it }
        Text(
          "This edit is just for your widget. Your Home Assistant dashboard stays as it is.",
          style = MaterialTheme.typography.bodySmall,
        )
        installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!pinSupported) {
          Text(
            "Your launcher doesn't support widget pinning. Install a widget " +
              "from the system widget picker instead.",
            style = MaterialTheme.typography.bodySmall,
          )
        }

        OutlinedButton(
          onClick = {
            pinScope.launch {
              if (isCardPinned) {
                pinStore.unpinCard(pinKey)
              } else {
                pinStore.pinCard(
                  MobilePinnedCard(
                    key = pinKey,
                    baseUrl = baseUrl,
                    dashboardUrlPath = dashboardUrlPath,
                    card = cardData,
                  )
                )
              }
            }
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(
            imageVector = if (isCardPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = null,
          )
          Text(text = if (isCardPinned) "  Unpin from Wear" else "  Pin to Wear")
        }

        onMonitor?.let { start ->
          OutlinedButton(
            onClick = {
              start()
              onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Monitor for 15 min")
          }
        }
      }
      Button(
        onClick = {
          if (installer.requestPin(baseUrl, editedCard, renderedPreview)) {
            requestedIds = installed.map { it.widgetId }.toSet()
          } else installError = "Your launcher could not start installation. Try again."
        },
        enabled = pinSupported && !capHit && renderedPreview != null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
      ) {
        Text(if (capHit) "All 5 slots in use" else "Install widget")
      }
    }
  }
}
