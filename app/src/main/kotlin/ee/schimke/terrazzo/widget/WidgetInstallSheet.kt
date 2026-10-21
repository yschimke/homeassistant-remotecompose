@file:Suppress("RestrictedApi")

package ee.schimke.terrazzo.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ee.schimke.ha.model.CardConfig
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.core.widget.WidgetStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Puts a card on the launcher: its live widget preview first, sized on a launcher grid, then an
 * optional label, then the one button that asks the launcher to place it. Nothing else — the watch,
 * the card style and monitoring live in the card's actions sheet, so this sheet does one job.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetInstallSheet(
  baseUrl: String,
  dashboardUrlPath: String,
  card: CardConfig,
  snapshot: HaSnapshot,
  onDismiss: () -> Unit,
  refreshSnapshot: (suspend () -> HaSnapshot)? = null,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  val installer = remember { WidgetInstaller(context.applicationContext) }
  val store = LocalTerrazzoGraph.current.widgetStore
  val originalName = remember(card) { card.raw["name"]?.jsonPrimitive?.content ?: "" }
  var name by remember(card) { mutableStateOf(originalName) }
  val editedCard =
    remember(card, name) {
      if (name.isBlank() || name == (card.raw["name"]?.jsonPrimitive?.content ?: "")) card
      else
        card.copy(
          raw = kotlinx.serialization.json.JsonObject(card.raw + ("name" to JsonPrimitive(name)))
        )
    }
  var renderedPreview by
    remember(baseUrl, editedCard) { mutableStateOf<android.widget.RemoteViews?>(null) }
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

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
      Column(
        modifier =
          Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text("Home screen widget", style = MaterialTheme.typography.headlineSmall)
        WidgetPreparationPreview(baseUrl, editedCard, liveSnapshot) { renderedPreview = it }

        OutlinedTextField(
          value = name,
          onValueChange = {
            name = it
            installError = null
          },
          label = { Text("Label (optional)") },
          placeholder = { Text(originalName.ifBlank { "Keep the card's own name" }) },
          supportingText = { Text("Only this widget. Your dashboard stays as it is.") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions =
            KeyboardActions(
              onDone = {
                focusManager.clearFocus()
                keyboard?.hide()
              }
            ),
          modifier = Modifier.fillMaxWidth(),
        )
        installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!pinSupported) {
          Text(
            if (android.os.Build.VERSION.SDK_INT < WidgetInstaller.MIN_HOST_API)
              "Home screen widgets need Android 16 or later. You can still use this card in the app or on Wear."
            else
              "Your launcher doesn't support widget pinning. Install a widget from the system widget picker instead.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          if (capHit)
            "All ${WidgetStore.MAX_WIDGETS} widgets are in use. Remove one from your home " +
              "screen to add this card."
          else
            "Keeps itself up to date · ${WidgetStore.MAX_WIDGETS - installedCount} of " +
              "${WidgetStore.MAX_WIDGETS} widgets left",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
          onClick = {
            focusManager.clearFocus()
            keyboard?.hide()
            if (installer.requestPin(baseUrl, editedCard, renderedPreview)) {
              requestedIds = installed.map { it.widgetId }.toSet()
            } else installError = "Your launcher could not start installation. Try again."
          },
          enabled = pinSupported && !capHit && renderedPreview != null,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Add widget")
        }
      }
    }
  }
}
