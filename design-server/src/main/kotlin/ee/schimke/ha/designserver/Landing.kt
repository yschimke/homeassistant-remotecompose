package ee.schimke.ha.designserver

import ee.schimke.ha.designserver.Pages.esc

/**
 * What the front page links to. Both defaults are paths on ha.coo.ee, whose edge sends them to the
 * preview box that serves the card designer and the catalog. They are absolute so the links work
 * from a server running anywhere else, which has no such routes.
 * - [designerUrl]: the card designer, the HA-branded UI Builder.
 * - [catalogUrl]: every HA card and component, in the catalog.
 * - [storeUrl]: where to get the app; without one the page points at the source instead.
 */
data class SiteConfig(
  val designerUrl: String = DEFAULT_DESIGNER_URL,
  val catalogUrl: String = DEFAULT_CATALOG_URL,
  val storeUrl: String? = null,
) {
  companion object {
    const val DEFAULT_DESIGNER_URL = "https://ha.coo.ee/ui-builder/"
    const val DEFAULT_CATALOG_URL = "https://ha.coo.ee/homeassistant-remotecompose/"

    /** `DESIGNER_URL`, `CATALOG_URL` and `STORE_URL`. */
    fun fromEnvironment(env: Map<String, String> = System.getenv()): SiteConfig =
      SiteConfig(
        designerUrl = env["DESIGNER_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_DESIGNER_URL,
        catalogUrl = env["CATALOG_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_CATALOG_URL,
        storeUrl = env["STORE_URL"]?.takeIf { it.isNotBlank() },
      )
  }
}

/**
 * The front page: what the app is, then a look at the card designer. Server-rendered and
 * script-free like the browser; its screenshots are the Play listing's, bundled by
 * `:design-server:siteResources`.
 */
object Landing {
  data class Screenshot(val file: String, val caption: String)

  val SCREENSHOTS =
    listOf(
      Screenshot("01-home-light.png", "A Home Assistant dashboard in Terrazzo"),
      Screenshot("02-home-dark.png", "The same dashboard in dark mode"),
    )

  /** The card types the teaser shows first, when the library has them. */
  private val TEASER_TYPES =
    listOf("tile", "gauge", "entities", "thermostat", "glance", "weather-forecast", "button")

  private const val TEASER_SIZE = 6
  private const val SOURCE_URL = "https://github.com/yschimke/homeassistant-remotecompose"

  fun page(library: DesignLibrary, site: SiteConfig): String = buildString {
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Terrazzo</title>")
    append(
      "<meta name=\"description\" content=\"Native Home Assistant dashboards for Android, " +
        "with widgets, Wear OS tiles and cards you can design yourself.\">"
    )
    append("<style>$CSS</style></head><body><main>")

    append("<section class=\"hero\"><div class=\"pitch\">")
    append("<p class=\"eyebrow\">For Home Assistant</p>")
    append("<h1>Terrazzo</h1>")
    append(
      "<p class=\"lede\">Your Home Assistant dashboards, drawn natively on your phone. " +
        "No web view, no setup: sign in and the dashboards you already have are there.</p>"
    )
    append("<div class=\"actions\">")
    if (site.storeUrl != null)
      append("<a class=\"button\" href=\"${esc(site.storeUrl)}\">Get the app</a>")
    else append("<a class=\"button\" href=\"$SOURCE_URL\">Get the app</a>")
    append("<a class=\"button quiet\" href=\"#designer\">Design your own cards</a>")
    append("</div></div>")
    append("<div class=\"phones\">")
    for (shot in SCREENSHOTS) {
      append(
        "<img class=\"phone\" src=\"/site/${shot.file}\" alt=\"${esc(shot.caption)}\" " +
          "width=\"822\" height=\"1462\">"
      )
    }
    append("<a class=\"button quiet\" href=\"/studio/dashboard\">Edit a dashboard</a>")
    append("</div></section>")

    append("<section class=\"features\">")
    feature(
      "The dashboards you have",
      "Terrazzo reads your Lovelace dashboards and draws each card natively, so they look " +
        "like Home Assistant and feel like an app.",
    )
    feature(
      "Live, and quick",
      "Cards follow your entities as they change, and tapping one does what it does in " +
        "Home Assistant.",
    )
    feature(
      "Beyond the app",
      "Put a card on your home screen as a widget, or on your watch.",
    )
    feature(
      "Private",
      "The app talks only to your Home Assistant. No analytics, no ads, and your sign-in " +
        "stays on your phone.",
    )
    append("</section>")

    append("<section id=\"designer\" class=\"designer\">")
    append("<p class=\"eyebrow\">Card designer</p>")
    append("<h2>Make a card your own</h2>")
    append(
      "<p class=\"lede\">Start from any card on your dashboard and restyle it: rearrange it, " +
        "change its look, or turn a gauge into something only you would build. Your " +
        "entities come with it, so you design against real values.</p>"
    )
    val teaser = teaser(library)
    if (teaser.isNotEmpty()) {
      append("<div class=\"cards\">")
      for (design in teaser) {
        val image = design.catalog?.previews?.example ?: continue
        append(
          "<a href=\"/designs/${design.id}\"><img loading=\"lazy\" src=\"$API_BASE/$image\" " +
            "alt=\"${esc(design.key.cardType)} card\"></a>"
        )
      }
      append("</div>")
    }
    append("<div class=\"actions\">")
    append("<a class=\"button\" href=\"${esc(site.designerUrl)}\">Open the card designer</a>")
    append(
      "<a class=\"button quiet\" href=\"${esc(site.catalogUrl)}\">Browse cards and components</a>"
    )
    append("</div></section>")

    append("<footer>")
    append("<a href=\"$SOURCE_URL\">Source</a>")
    append("<a href=\"$SOURCE_URL/issues\">Feedback</a>")
    append(
      "<a href=\"https://yschimke.github.io/homeassistant-remotecompose/privacy/\">Privacy</a>"
    )
    append("<a href=\"/designs\">Card templates</a>")
    append("</footer>")
    append("</main></body></html>")
  }

  /**
   * One design per card type, light, first theme, with an example preview: the [TEASER_TYPES]
   * first, then the rest of the library, up to [TEASER_SIZE].
   */
  fun teaser(library: DesignLibrary): List<LibraryDesign> {
    val candidates =
      library.designs
        .filter { it.catalog?.previews?.example != null }
        .filter { ThemeParts.of(it.key).mode != "dark" }
        .groupBy { it.key.cardType }
        .mapValues { it.value.first() }
    val order = TEASER_TYPES.filter { it in candidates } + candidates.keys.sorted()
    return order.distinct().take(TEASER_SIZE).map { candidates.getValue(it) }
  }

  private fun StringBuilder.feature(title: String, text: String) {
    append("<div><h3>${esc(title)}</h3><p>${esc(text)}</p></div>")
  }

  private const val CSS =
    """
:root{--bg:#f6f7f9;--fg:#1b1d21;--muted:#5f6670;--card:#fff;--line:#dfe2e7;--accent:#1f6feb;--on-accent:#fff;--tint:#e8effc}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--fg:#e8eaed;--muted:#9aa1ab;--card:#1c1f24;--line:#2c3138;--accent:#6ea8fe;--on-accent:#0b1b33;--tint:#1a2433}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:1080px;margin:0 auto;padding:32px 16px 48px}a{color:var(--accent)}
h1{font-size:44px;line-height:1.1;margin:0 0 12px}h2{font-size:30px;line-height:1.2;margin:0 0 12px}h3{font-size:17px;margin:0 0 4px}
.eyebrow{margin:0 0 6px;color:var(--accent);font-weight:600;font-size:14px;letter-spacing:.04em;text-transform:uppercase}
.lede{font-size:18px;color:var(--muted);max-width:36em;margin:0 0 20px}
.hero{display:grid;grid-template-columns:1fr 400px;gap:40px;align-items:center;padding:16px 0 40px}
.phones{display:grid;grid-template-columns:1fr 1fr;gap:16px}.phones img:last-child{margin-top:48px}
.phone{width:100%;height:auto;border-radius:24px;border:6px solid var(--fg);background:var(--card)}
.actions{display:flex;flex-wrap:wrap;gap:12px}
.button{display:inline-block;padding:11px 20px;border-radius:999px;background:var(--accent);color:var(--on-accent);font-weight:600;text-decoration:none}
.button.quiet{background:none;color:var(--accent);border:1px solid var(--line)}
.features{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:24px;padding:32px 0;border-top:1px solid var(--line)}
.features p{margin:0;color:var(--muted)}
.designer{background:var(--tint);border-radius:24px;padding:32px;margin-top:8px}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:12px;align-items:start;margin:0 0 24px}
.cards img{display:block;width:100%;height:auto;border-radius:12px;background:var(--card)}
footer{display:flex;flex-wrap:wrap;gap:20px;padding:32px 0 0;font-size:14px}
@media (max-width:760px){.hero{grid-template-columns:1fr}.phones{max-width:360px}h1{font-size:36px}.designer{padding:20px}}
"""
}
