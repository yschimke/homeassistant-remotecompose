package ee.schimke.terrazzo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import ee.schimke.ha.model.HaSnapshot
import ee.schimke.ha.rc.HighRiskActions
import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.decodeHaAction
import ee.schimke.ha.rc.encodeHaAction
import ee.schimke.terrazzo.core.prefs.CardStylePref
import ee.schimke.terrazzo.core.prefs.DarkModePref
import ee.schimke.terrazzo.core.prefs.ThemePref
import ee.schimke.terrazzo.terrazzoGraph
import ee.schimke.terrazzo.ui.TerrazzoTheme
import ee.schimke.terrazzo.ui.toThemeStyle
import ee.schimke.terrazzo.ui.toUberTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A launcher widget's high-risk tap ([HighRiskActions]: a lock, a garage door): it opens this
 * activity rather than the background receiver, so the phone's lock mode is read when the tap
 * lands. Unlocked (write mode on, as the dashboard's lock chip or Settings leave it), the call runs
 * at once and the activity closes without drawing; locked, it asks first.
 *
 * Not exported, and reached only through the widget's immutable `PendingIntent`, whose extras the
 * launcher can't replace: the action is the one the app put on the widget, as [trustedWidgetAction]
 * requires of the receiver's.
 */
class WidgetConfirmActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    val action = decodeHaAction(intent.getStringExtra(EXTRA_ACTION))?.takeIf(::confirms)
    if (action == null || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish()
      return
    }
    val graph = terrazzoGraph()
    lifecycleScope.launch {
      val writeMode =
        graph.sessionWriteMode.writeMode.value ?: graph.preferencesStore.permanentWriteMode.first()
      if (writeMode) {
        run(applicationContext, widgetId, action, confirmed = false)
        finish()
        return@launch
      }
      val entry = graph.widgetStore.get(widgetId)
      val snapshot = entry?.let { graph.offlineCache.latestSnapshot(it.baseUrl) }
      val prompt = widgetConfirmPrompt(action, snapshot)
      setContent {
        val themePref by
          graph.preferencesStore.themeStyle.collectAsState(initial = ThemePref.Material3)
        val darkPref by
          graph.preferencesStore.darkMode.collectAsState(initial = DarkModePref.Follow)
        val cardStylePref by
          graph.preferencesStore.cardStyle.collectAsState(initial = CardStylePref.Classic)
        TerrazzoTheme(themePref.toThemeStyle(), darkPref, cardStylePref.toUberTheme()) {
          AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(prompt) },
            text = { Text("Controls are locked. Unlock them in the app to skip this step.") },
            confirmButton = {
              TextButton(
                onClick = {
                  run(applicationContext, widgetId, action, confirmed = true)
                  finish()
                }
              ) {
                Text(widgetConfirmVerb(action))
              }
            },
            dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
          )
        }
      }
    }
  }

  companion object {
    private val confirmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val EXTRA_WIDGET_ID = "ee.schimke.terrazzo.widget.CONFIRM_WIDGET_ID"
    private const val EXTRA_ACTION = "ee.schimke.terrazzo.widget.CONFIRM_ACTION"

    /** Whether [action] is one this activity runs: a high-risk toggle or service call. */
    internal fun confirms(action: HaAction): Boolean =
      action is HaAction.Toggle || action is HaAction.CallService

    /** The explicit intent a high-risk widget tap opens, carrying the app's own [action]. */
    internal fun intent(context: Context, widgetId: Int, action: HaAction): Intent =
      Intent(context, WidgetConfirmActivity::class.java)
        .putExtra(EXTRA_WIDGET_ID, widgetId)
        .putExtra(EXTRA_ACTION, encodeHaAction(action))

    private fun run(context: Context, widgetId: Int, action: HaAction, confirmed: Boolean) {
      context
        .terrazzoGraph()
        .logStore
        .recordLocalAction(
          if (confirmed) "Widget action confirmed" else "Widget action (unlocked)",
          actionEntityId(action),
        )
      confirmScope.launch { WidgetActionRunner.run(context, widgetId, action) }
    }
  }
}

/** The question asked before [action] runs: `Unlock Front door?`. */
internal fun widgetConfirmPrompt(action: HaAction, snapshot: HaSnapshot?): String {
  val entityId = actionEntityId(action)
  val name =
    entityId?.let {
      (snapshot?.states?.get(it)?.attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull
    } ?: entityId ?: "this"
  return "${widgetConfirmVerb(action)} $name?"
}

/** The confirm button's verb for [action]: `Unlock`, `Open`, else `Run`. */
internal fun widgetConfirmVerb(action: HaAction): String =
  when (action) {
    is HaAction.Toggle -> "Toggle"
    is HaAction.CallService ->
      when (action.service) {
        "unlock" -> "Unlock"
        "lock" -> "Lock"
        "open" -> "Open"
        "open_cover" -> "Open"
        "close_cover" -> "Close"
        "toggle" -> "Toggle"
        else -> "Run"
      }
    else -> "Run"
  }
