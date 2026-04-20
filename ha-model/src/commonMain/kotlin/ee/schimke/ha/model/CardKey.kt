package ee.schimke.ha.model

import kotlinx.serialization.Serializable

/**
 * Stable identity for a single card within a dashboard, so a widget or
 * cache entry can keep pointing at the same card as dashboards evolve.
 *
 * HA does not assign per-card UUIDs, so we key by the user-facing path
 * (`dashboard url_path` + `view path` + `section/card index`) plus the
 * `type` as a sanity field. If the user reorders cards the key changes,
 * which is intentional — treat a reordered layout as a new widget target.
 */
@Serializable
data class CardKey(
    val dashboardUrlPath: String?,
    val viewPath: String?,
    val sectionIndex: Int? = null,
    val cardIndex: Int,
    val type: String,
) {
    fun toCacheKey(): String = buildString {
        append(dashboardUrlPath ?: "_default")
        append('/')
        append(viewPath ?: "_default")
        if (sectionIndex != null) {
            append('/')
            append('s')
            append(sectionIndex)
        }
        append('/')
        append(cardIndex)
        append('#')
        append(type)
    }
}
