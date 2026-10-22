package ee.schimke.terrazzo.assistant

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionManager
import androidx.core.content.edit

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

  /** Enables or disables every function; returns false (and changes nothing) if that failed. */
  suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
    val manager = AppFunctionManager.getInstance(context) ?: return false
    val state =
      if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED
      else AppFunctionManager.APP_FUNCTION_STATE_DISABLED
    return runCatching { functionIds().forEach { manager.setAppFunctionEnabled(it, state) } }
      .onFailure { Log.w(TAG, "Couldn't ${if (enabled) "enable" else "disable"} AppFunctions", it) }
      .onSuccess { prefs(context).edit { putBoolean(KEY_ENABLED, enabled) } }
      .isSuccess
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private const val PREFS = "terrazzo_assistant"
  private const val KEY_ENABLED = "app_functions_enabled"
  private const val TAG = "AssistantAccess"
}
