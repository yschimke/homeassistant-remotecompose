package ee.schimke.terrazzo.assistant

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionManager
import androidx.core.content.edit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The Settings switch behind [TerrazzoAppFunctions]: every function is declared disabled, and this
 * enables or disables them together through the platform, which keeps the state across restarts.
 * The switch's own position is kept in shared preferences only to draw it, since the platform's
 * per-function state can't be read back by the app that owns it.
 */
internal object AssistantAccess {

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun functionIds() =
    listOf(
      TerrazzoAppFunctionService.FUNCTION_ID_FIND_ENTITIES,
      TerrazzoAppFunctionService.FUNCTION_ID_GET_ENTITY_STATE,
      TerrazzoAppFunctionService.FUNCTION_ID_TURN_ON,
      TerrazzoAppFunctionService.FUNCTION_ID_TURN_OFF,
      TerrazzoAppFunctionService.FUNCTION_ID_TOGGLE,
    )

  /** Whether this device runs AppFunctions at all: Android 16 or newer, outside a work profile. */
  fun isSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
      AppFunctionManager.getInstance(context) != null

  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  /**
   * Enables or disables every function; returns false if that failed. A failure part-way (or a
   * cancelled call) disables them all again, so the functions are never on while the switch is off.
   */
  suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    val manager = AppFunctionManager.getInstance(context) ?: return false
    val state =
      if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED
      else AppFunctionManager.APP_FUNCTION_STATE_DISABLED
    try {
      functionIds().forEach { manager.setAppFunctionEnabled(it, state) }
    } catch (failure: Throwable) {
      Log.w(TAG, "Couldn't ${if (enabled) "enable" else "disable"} AppFunctions", failure)
      withContext(NonCancellable) {
        functionIds().forEach {
          runCatching {
            manager.setAppFunctionEnabled(it, AppFunctionManager.APP_FUNCTION_STATE_DISABLED)
          }
        }
        prefs(context).edit { putBoolean(KEY_ENABLED, false) }
      }
      if (failure is CancellationException) throw failure
      return false
    }
    prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    return true
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private const val PREFS = "terrazzo_assistant"
  private const val KEY_ENABLED = "app_functions_enabled"
  private const val TAG = "AssistantAccess"
}
