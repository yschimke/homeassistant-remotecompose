package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.TemplateUsage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.time.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Template usage ([UsageStats]):
 * - `POST /designs/v1/usage` takes an app's bucketed day of renders ([UsageReportCheck]), from
 *   users who turned sharing on. Capped in size and rate; nothing about the caller is kept.
 * - `/stats/templates` (HTML) and `/api/stats/templates` (JSON): the last [STATS_DAYS] days'
 *   counts, each backed by at least [UsageStats.K_ANONYMITY] requests or reports, for the GitHub
 *   logins in [statsUsers] only. Without GitHub sign-in or with no such logins they don't exist.
 */
fun Route.usageRoutes(usage: UsageStats, github: GithubAuth?, statsUsers: Set<String>) {
  val limit = UsageRateLimit()

  // The web app posts its report from the page's own origin (the Home Assistant panel or the hosted
  // site), so the endpoint answers a CORS preflight for its JSON body.
  options("$API_BASE/${TemplateUsage.USAGE_PATH}") {
    call.allowAnyOrigin()
    call.response.header(HttpHeaders.AccessControlAllowMethods, "POST, OPTIONS")
    call.response.header(HttpHeaders.AccessControlAllowHeaders, "Content-Type")
    call.respondText("", status = HttpStatusCode.NoContent)
  }

  post("$API_BASE/${TemplateUsage.USAGE_PATH}") {
    call.allowAnyOrigin()
    if (!limit.allow()) {
      return@post call.respondText("slow down", status = HttpStatusCode.TooManyRequests)
    }
    val body = call.receiveText()
    if (body.length > MAX_USAGE_BYTES) {
      return@post call.respondText("too large", status = HttpStatusCode.PayloadTooLarge)
    }
    UsageReportCheck.parse(body, usage.today())
      .onSuccess {
        usage.reportReceived(it)
        call.respondText("", status = HttpStatusCode.NoContent)
      }
      .onFailure {
        call.respondText("bad report: ${it.message}", status = HttpStatusCode.BadRequest)
      }
  }

  if (github == null || statsUsers.isEmpty()) return

  suspend fun ApplicationCall.statsReader(): Boolean {
    val login = github.login(this)
    return when {
      login == null -> {
        respondRedirect(github.signInPath(request.local.uri))
        false
      }
      statsUsers.none { it.equals(login, ignoreCase = true) } -> {
        notFound()
        false
      }
      else -> true
    }
  }

  fun range(): Pair<String, String> {
    val to = usage.today()
    return LocalDate.parse(to).minusDays(STATS_DAYS - 1).toString() to to
  }

  get("/api/stats/templates") {
    if (!call.statsReader()) return@get
    val (from, to) = range()
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      statsJson.encodeToString(ListSerializer(UsageCount.serializer()), usage.rows(from, to)),
      ContentType.Application.Json,
    )
  }

  get("/stats/templates") {
    if (!call.statsReader()) return@get
    val (from, to) = range()
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(StatsPage.html(from, to, usage.rows(from, to)), ContentType.Text.Html)
  }
}

/** The days `/stats/templates` covers, today included. */
private const val STATS_DAYS: Long = 30

/** A report is a few dozen rows; anything near this is not one. */
private const val MAX_USAGE_BYTES = 64 * 1024

private val statsJson = Json { prettyPrint = true }

/**
 * `/stats/templates`: per app, which templates were fetched and rendered, with each one's share of
 * that app's fetches or renders, and the mix of sources renders came from.
 */
object StatsPage {
  fun html(from: String, to: String, rows: List<UsageCount>): String = buildString {
    val esc = Pages::esc
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Template usage</title></head><body>")
    append("<h1>Template usage</h1>")
    append(
      "<p>${esc(from)} to ${esc(to)} (UTC). Only counts with ${UsageStats.K_ANONYMITY} or more " +
        "requests or reports behind them are shown.</p>"
    )
    if (rows.isEmpty()) {
      append("<p>Nothing to show yet.</p></body></html>")
      return@buildString
    }
    for ((app, appRows) in rows.groupBy { "${it.cell.app} ${it.cell.platform}".trim() }) {
      append("<h2>${esc(app)}</h2>")
      val manifests = appRows.filter { it.cell.kind == UsageStats.MANIFEST }.sumOf { it.count }
      append("<p>Manifest checks: $manifests</p>")
      table(
        "Fetched templates",
        appRows.filter { it.cell.kind == UsageStats.FETCH },
        listOf("Card", "Variant", "Style", "Surface"),
      ) {
        listOf(it.cardType, it.variant, it.style, it.designSurface)
      }
      table(
        "Rendered cards (device-days)",
        appRows.filter { it.cell.kind == UsageStats.RENDER },
        listOf("Surface", "Card", "Variant", "Style", "Source", "Cards a day"),
      ) {
        listOf(it.surface, it.cardType, it.variant, it.style, it.source, it.bucket)
      }
      val renders = appRows.filter { it.cell.kind == UsageStats.RENDER }
      if (renders.isNotEmpty()) {
        val total = renders.sumOf { it.count }.toDouble()
        append("<h3>Source mix</h3><ul>")
        renders
          .groupBy { it.cell.source }
          .mapValues { (_, r) -> r.sumOf { it.count } }
          .entries
          .sortedByDescending { it.value }
          .forEach { (source, count) ->
            append("<li>${esc(source)}: ${percent(count / total)}</li>")
          }
        append("</ul>")
      }
    }
    append("</body></html>")
  }

  private fun StringBuilder.table(
    title: String,
    rows: List<UsageCount>,
    headings: List<String>,
    cells: (UsageCell) -> List<String>,
  ) {
    if (rows.isEmpty()) return
    val esc = Pages::esc
    val total = rows.sumOf { it.count }.toDouble()
    append("<h3>${esc(title)}</h3><table><tr>")
    headings.forEach { append("<th>${esc(it)}</th>") }
    append("<th>Count</th><th>Share</th></tr>")
    for (row in rows.sortedByDescending { it.count }) {
      append("<tr>")
      cells(row.cell).forEach { append("<td>${esc(it)}</td>") }
      append("<td>${row.count}</td><td>${percent(row.count / total)}</td></tr>")
    }
    append("</table>")
  }

  private fun percent(share: Double): String = "%.1f%%".format(share * 100)
}
