package ee.schimke.terrazzo.dashboard

import ee.schimke.ha.model.Dashboard

/**
 * A Lovelace `navigate` path (`/dashboard-bambu/p2s`, `/lovelace/0`, `/config/areas`) split into
 * the dashboard and view it names. HA serves the default dashboard at `lovelace`, which the app
 * keys as a null url path; the query string and fragment are dropped.
 */
data class LovelacePath(val dashboard: String, val view: String?) {
  /** The app's url path for [dashboard]: null for HA's default dashboard. */
  val dashboardUrlPath: String?
    get() = dashboard.takeIf { it != DEFAULT_DASHBOARD }

  /** Whether this path is inside the dashboard the app keys as [urlPath]. */
  fun isIn(urlPath: String?): Boolean = dashboardUrlPath == urlPath

  companion object {
    const val DEFAULT_DASHBOARD = "lovelace"

    /** Null for a path that isn't app-relative (`https://…`, `//host/…`) or names nothing. */
    fun parse(path: String): LovelacePath? {
      if (!path.startsWith("/") || path.startsWith("//")) return null
      val segments =
        path.substringBefore('#').substringBefore('?').split('/').filter { it.isNotEmpty() }
      val dashboard = segments.firstOrNull() ?: return null
      return LovelacePath(dashboard, segments.getOrNull(1))
    }
  }
}

/**
 * The index of the view [segment] names in this dashboard, as HA resolves it: a view's `path`, else
 * its position. A null segment is the first view.
 */
fun Dashboard.viewIndexOf(segment: String?): Int? {
  if (views.isEmpty()) return null
  if (segment == null) return 0
  views
    .indexOfFirst { it.path == segment }
    .takeIf { it >= 0 }
    ?.let {
      return it
    }
  return segment.toIntOrNull()?.takeIf { it in views.indices }
}

/**
 * An app-relative [path] (`/history`) against the Home Assistant [baseUrl], null when there is no
 * http(s) base to resolve it against; any other link is returned as it is.
 */
fun resolveAgainstBase(baseUrl: String?, path: String): String? {
  if (!path.startsWith("/") || path.startsWith("//")) return path
  val base =
    baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
  return base.trimEnd('/') + path
}
