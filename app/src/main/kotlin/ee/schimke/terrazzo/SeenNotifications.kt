package ee.schimke.terrazzo

import android.content.Context

/**
 * The Home Assistant notifications already announced, per instance, kept across launches.
 *
 * The dashboard pops a snackbar for a notification that arrives while it is open and treats what is
 * already there as seen. In memory, "already there" was the session's first emission — an empty
 * list, since a cache-only session has no notifications and a live one fetches them a moment later
 * — so every notification popped again on every cold start.
 */
internal class SeenNotifications(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The ids announced for [baseUrl], or null before its first non-empty list. */
  fun seen(baseUrl: String): Set<String>? = prefs.getStringSet(baseUrl, null)

  /** Records [ids] as announced for [baseUrl], dropping ids Home Assistant no longer has. */
  fun record(baseUrl: String, ids: Set<String>) {
    prefs.edit().putStringSet(baseUrl, ids).apply()
  }

  private companion object {
    const val PREFS = "terrazzo_seen_notifications"
  }
}
