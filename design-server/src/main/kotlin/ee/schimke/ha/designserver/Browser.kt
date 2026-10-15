package ee.schimke.ha.designserver

import ee.schimke.ha.design.DesignKey
import io.ktor.http.Parameters
import java.net.URLEncoder
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A design's theme, split: baked themes are `baked:<style>:<light|dark>`, card styles with their
 * own palette `style:<card style>:<light|dark>`, which the browser lists as that style.
 */
data class ThemeParts(val style: String, val mode: String?) {
  companion object {
    fun of(key: DesignKey): ThemeParts {
      val parts = key.theme.split(':')
      return if (parts.size == 3 && (parts[0] == "baked" || parts[0] == "style"))
        ThemeParts(parts[1], parts[2])
      else ThemeParts(key.theme, null)
    }
  }
}

/**
 * The browser's filter, from query parameters: card type, theme style, light/dark, a text search
 * over type and properties, and which preview to show. With [defaults], an unset style and mode
 * pick the library's first style in light, so the first page shows each shape once.
 */
data class BrowserFilter(
  val cardType: String?,
  val style: String?,
  val mode: String?,
  val query: String?,
  val preview: Preview,
) {
  enum class Preview {
    Example,
    Template,
  }

  fun apply(library: DesignLibrary): List<LibraryDesign> =
    library.designs.filter { design ->
      val theme = ThemeParts.of(design.key)
      (cardType == null || design.key.cardType == cardType) &&
        (style == null || theme.style == style) &&
        (mode == null || theme.mode == mode) &&
        (query == null ||
          design.key.cardType.contains(query, ignoreCase = true) ||
          design.key.shape.contains(query, ignoreCase = true))
    }

  /** This filter as a query string, with [change] applied: for links that keep the rest. */
  fun query(change: BrowserFilter.() -> BrowserFilter = { this }): String {
    val f = change()
    val params =
      listOfNotNull(
        "type" to (f.cardType ?: ALL),
        "style" to (f.style ?: ALL),
        "mode" to (f.mode ?: ALL),
        f.query?.let { "q" to it },
        ("preview" to "template").takeIf { f.preview == Preview.Template },
      )
    return params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
  }

  companion object {
    const val ALL = "all"

    /** The query keys the browser reads. */
    val KEYS = setOf("type", "style", "mode", "q", "preview")

    fun from(params: Parameters, library: DesignLibrary, defaults: Boolean): BrowserFilter {
      fun param(name: String, default: String?): String? =
        when (val value = params[name]?.trim()) {
          null,
          "" -> default
          ALL -> null
          else -> value
        }
      val firstStyle = library.designs.firstOrNull()?.let { ThemeParts.of(it.key).style }
      val firstMode = library.designs.firstNotNullOfOrNull { ThemeParts.of(it.key).mode }
      return BrowserFilter(
        cardType = param("type", null),
        style = param("style", firstStyle.takeIf { defaults }),
        mode = param("mode", firstMode.takeIf { defaults }),
        query = param("q", null),
        preview = if (params["preview"] == "template") Preview.Template else Preview.Example,
      )
    }
  }
}

/** The JSON API: the browser's data for tools and scripts. */
object Api {
  private val json = Json { prettyPrint = true }

  fun list(library: DesignLibrary, designs: List<LibraryDesign>): String =
    json.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("serial", library.manifest.serial)
        put("count", designs.size)
        putJsonArray("designs") { designs.forEach { add(summary(it)) } }
      },
    )

  fun detail(library: DesignLibrary, design: LibraryDesign): String {
    val decoded = library.decode(design)
    return json.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        summary(design).forEach { (k, v) -> put(k, v) }
        putJsonArray("bindings") {
          decoded.bindings.forEach { b ->
            add(
              buildJsonObject {
                put("name", b.name)
                put("type", b.type)
              }
            )
          }
        }
        put("operations", decoded.operations)
        putJsonArray("violations") { decoded.violations.forEach { add(JsonPrimitive(it)) } }
        decoded.error?.let { put("error", it) }
        design.catalog?.example?.let { example ->
          putJsonObject("example") {
            put("source", example.source)
            put("card", example.card)
          }
        }
      },
    )
  }

  private fun summary(design: LibraryDesign): JsonObject = buildJsonObject {
    put("id", design.id)
    put("sha256", design.entry.sha256)
    put("blob", "$API_BASE/blobs/${design.entry.sha256}.rc")
    put("key", Json.encodeToJsonElement(DesignKey.serializer(), design.key))
    putJsonObject("properties") { design.properties.forEach { (k, v) -> put(k, v) } }
    put("slots", design.entry.slots)
    put("bytes", design.entry.bytes)
    put("revoked", design.entry.revoked)
    putJsonObject("previews") {
      design.catalog?.previews?.example?.let { put("example", "$API_BASE/$it") }
      design.catalog?.previews?.template?.let { put("template", "$API_BASE/$it") }
    }
  }
}

/** The template browser's HTML. Server-rendered, no scripts: it works from a phone too. */
object Pages {
  fun browser(library: DesignLibrary, filter: BrowserFilter): String {
    val designs = filter.apply(library)
    val all = library.designs
    val types = all.map { it.key.cardType }.distinct().sorted()
    val styles = all.map { ThemeParts.of(it.key).style }.distinct()
    val modes = all.mapNotNull { ThemeParts.of(it.key).mode }.distinct()
    val shapes = all.map { it.key.cardType to it.key.shape }.distinct().size
    return page("Card designs") {
      append(
        "<nav><a href=\"/\">← Terrazzo</a></nav><header><h1>Card designs</h1><p class=\"stats\">"
      )
      append("${all.size} designs · $shapes shapes · ${types.size} card types · ")
      append(
        "${all.map { it.entry.sha256 }.distinct().size} blobs (${kib(library.totalBlobBytes)}) · "
      )
      append(
        "serial ${library.manifest.serial} · generated ${date(library.manifest.generatedAtEpochMs)}"
      )
      append(
        "</p><p class=\"hint\">App design server URL: <code>&lt;this host&gt;$API_BASE</code> · " +
          "<a href=\"/schema\">Schema from a card's JSON</a></p></header>"
      )

      append("<form class=\"filters\" method=\"get\" action=\"/designs\">")
      select("type", "Card", types, filter.cardType)
      select("style", "Theme", styles, filter.style)
      select("mode", "Mode", modes, filter.mode)
      append(
        "<label>Search <input type=\"search\" name=\"q\" value=\"${esc(filter.query.orEmpty())}\" placeholder=\"type or property\"></label>"
      )
      append("<label>Preview <select name=\"preview\">")
      option("example", "With example values", filter.preview == BrowserFilter.Preview.Example)
      option("template", "Template placeholders", filter.preview == BrowserFilter.Preview.Template)
      append("</select></label><button type=\"submit\">Apply</button></form>")

      append("<p class=\"count\">${designs.size} shown</p>")
      if (designs.isEmpty()) append("<p class=\"empty\">No designs match.</p>")
      for ((type, group) in designs.groupBy { it.key.cardType }.toSortedMap()) {
        append(
          "<section><h2 id=\"${esc(type)}\">${esc(type)} <span>${group.size}</span></h2><div class=\"grid\">"
        )
        for (design in group) tile(design, filter.preview)
        append("</div></section>")
      }
    }
  }

  fun detail(
    library: DesignLibrary,
    design: LibraryDesign,
    editor: EditorState = EditorState.Off,
  ): String {
    val decoded = library.decode(design)
    val key = design.key
    val theme = ThemeParts.of(key)
    return page("${key.cardType} · card design") {
      append(
        "<nav><a href=\"/designs?type=${enc(key.cardType)}&style=all&mode=all\">← ${esc(key.cardType)} designs</a></nav>"
      )
      append("<h1>${esc(key.cardType)}</h1>")
      append("<p class=\"chips\">")
      design.properties.forEach { (k, v) -> append(chip(k, v)) }
      if (design.properties.isEmpty())
        append("<span class=\"muted\">no generation properties</span>")
      append("</p>")

      append("<div class=\"previews\">")
      design.catalog?.previews?.example?.let { figure("$API_BASE/$it", "With example values") }
      design.catalog?.previews?.template?.let { figure("$API_BASE/$it", "Template placeholders") }
      if (design.catalog == null)
        append("<p class=\"muted\">No previews: this library has no catalog.</p>")
      append("</div>")

      append("<h2>Key</h2><table>")
      row("Card type", esc(key.cardType))
      row("Variant", esc(key.variant))
      row("Surface", esc(key.surface.wire))
      row("Theme", esc(theme.style) + (theme.mode?.let { " · ${esc(it)}" } ?: ""))
      row("RemoteCompose API level", key.rcApiLevel.toString())
      row("Shape", "<code>${esc(key.shape.ifEmpty { "(none)" })}</code>")
      row("Slots", design.entry.slots.toString())
      row(
        "Blob",
        "<a href=\"$API_BASE/blobs/${design.entry.sha256}.rc\"><code>${design.entry.sha256}</code></a> · ${kib(design.entry.bytes.toLong())}",
      )
      if (design.entry.revoked) row("Status", "<strong>revoked</strong>")
      append("</table>")

      val siblings = library.siblings(design)
      if (siblings.isNotEmpty()) {
        append("<h2>Other themes</h2><div class=\"grid small\">")
        for (sibling in siblings) tile(sibling, BrowserFilter.Preview.Example, compact = true)
        append("</div>")
      }

      append("<h2>Bindings <span>${decoded.bindings.size}</span></h2>")
      append(
        "<p class=\"muted\">Named values a client pushes to show a card's values. <code>&lt;domain&gt;.s&lt;N&gt;</code> is slot N's entity; <code>card.*</code> is the card's own config.</p>"
      )
      if (decoded.error != null)
        append("<p class=\"error\">Couldn't decode the template: ${esc(decoded.error)}</p>")
      if (decoded.bindings.isNotEmpty()) {
        append("<table><tr><th>Name</th><th>Type</th></tr>")
        for (b in decoded.bindings) append(
          "<tr><td><code>${esc(b.name)}</code></td><td>${esc(b.type)}</td></tr>"
        )
        append("</table>")
      }

      append("<h2>Document</h2><table>")
      row("Operations", decoded.operations.toString())
      row(
        "Mobile-app op set",
        if (decoded.violations.isEmpty()) "valid"
        else
          "<span class=\"error\">${decoded.violations.size} violations</span><ul>" +
            decoded.violations.take(20).joinToString("") { "<li>${esc(it)}</li>" } +
            "</ul>",
      )
      append("</table>")
      if (decoded.sizeByOperation.isNotEmpty()) {
        append("<details><summary>Bytes by operation</summary><table>")
        decoded.sizeByOperation.forEach { (op, bytes) ->
          append("<tr><td>${esc(op)}</td><td>$bytes</td></tr>")
        }
        append("</table></details>")
      }

      design.catalog?.example?.let { example ->
        append(
          "<h2>Example card</h2><p class=\"muted\">From <code>${esc(example.source)}</code>: a card whose generation properties give this shape.</p>"
        )
        append(
          "<pre>${esc(prettyJson.encodeToString(JsonObject.serializer(), example.card))}</pre>"
        )
      }
      append("<h2>Design it</h2>")
      append(
        "<p class=\"muted\">The card's data contract — its slots and every binding with an example " +
          "value — as <a href=\"/api/designs/${design.id}/schema\">JSON</a>.</p>"
      )
      designButton(editor, "design", design.id, CardSchema.fromDesign(library, design))
      append("<p class=\"muted\"><a href=\"/api/designs/${design.id}\">JSON</a></p>")
    }
  }

  /**
   * The schema page: paste a card's Lovelace config (optionally with its entities' states) and see
   * its data contract, then seed a UI Builder design from it.
   */
  fun schema(input: String?, schema: CardSchema?, error: String?, editor: EditorState): String =
    page("Card schema") {
      append("<nav><a href=\"/designs\">← Card designs</a></nav><h1>Card schema</h1>")
      append(
        "<p class=\"muted\">Paste a card's Lovelace config, or <code>{\"card\": {…}, \"states\": " +
          "{\"light.kitchen\": {\"state\": \"on\", \"attributes\": {…}}}}</code>. The schema is what a " +
          "design for it can bind: one slot per entity, named by position, and the values a client " +
          "pushes for each.</p>"
      )
      append("<form method=\"post\" action=\"/schema\" class=\"stack\">")
      append(
        "<textarea name=\"card\" rows=\"10\" spellcheck=\"false\" placeholder=\"{&quot;type&quot;: &quot;entities&quot;, &quot;entities&quot;: [&quot;light.kitchen&quot;]}\">"
      )
      append(esc(input.orEmpty()))
      append("</textarea><button type=\"submit\">Show schema</button></form>")
      if (error != null) append("<p class=\"error\">${esc(error)}</p>")
      if (schema != null) {
        schemaTables(schema)
        designButton(editor, "card", input.orEmpty(), schema)
      }
    }

  private fun StringBuilder.schemaTables(schema: CardSchema) {
    for (note in schema.notes) append("<p class=\"muted\">${esc(note)}</p>")
    append("<h2>Slots <span>${schema.slots.size}</span></h2>")
    if (schema.slots.isEmpty())
      append("<p class=\"muted\">No entities: only card-level bindings.</p>")
    else {
      append("<table><tr><th>Slot</th><th>Entity</th></tr>")
      for (slot in schema.slots) append(
        "<tr><td><code>${esc(slot.slot)}</code></td><td>${esc(slot.entity ?: "")}${slot.name?.let { " · " + esc(it) } ?: ""}</td></tr>"
      )
      append("</table>")
    }
    append(
      "<h2>Bindings <span>${schema.bindings.size}</span></h2><table><tr><th>Name</th><th>Type</th><th>Example</th></tr>"
    )
    for (b in schema.bindings) {
      val type =
        b.type.name.lowercase() +
          if (UiBuilderSeed.supports(b.type)) ""
          else " <span class=\"muted\">(not in UI Builder yet)</span>"
      append(
        "<tr><td><code>${esc(b.name)}</code></td><td>$type</td><td>${esc(b.example?.content ?: "")}</td></tr>"
      )
    }
    append("</table>")
  }

  /** "Design in UI Builder" for a library design or a card: a form that seeds and opens one. */
  private fun StringBuilder.designButton(
    editor: EditorState,
    field: String,
    value: String,
    schema: CardSchema,
  ) {
    when {
      !editor.enabled ->
        append(
          "<p class=\"muted\">Run the server with UI Builder (<code>UI_BUILDER_BINARY</code> or <code>UI_BUILDER_URL</code>) to design this card's content there.</p>"
        )
      !editor.authorized && editor.signInPath != null ->
        append(
          "<p><a href=\"${esc(editor.signInPath)}\">Sign in with GitHub</a> " +
            "<span class=\"muted\">to design this card in UI Builder. Your designs are yours: " +
            "shared with your GitHub account and nobody else.</span></p>"
        )
      !editor.authorized ->
        append(
          "<p class=\"muted\">UI Builder is available: open the edit link the server printed at startup to design this card there.</p>"
        )
      else -> {
        append("<form method=\"post\" action=\"/ui-builder/designs\">")
        append("<input type=\"hidden\" name=\"$field\" value=\"${esc(value)}\">")
        append("<button type=\"submit\">Design in UI Builder</button> ")
        editor.login?.let {
          append(
            "<span class=\"muted\">as <b>${esc(it)}</b> · <button form=\"sign-out\" " +
              "class=\"link\" type=\"submit\">sign out</button></span> "
          )
        }
        append(
          "<span class=\"muted\">Seeds an adaptive RemoteCompose layout bound to these values.</span></form>"
        )
        if (editor.login != null)
          append(
            "<form id=\"sign-out\" method=\"post\" action=\"${GithubAuth.SIGN_OUT_PATH}\"></form>"
          )
        val unsupported = UiBuilderSeed.unsupported(schema)
        if (unsupported.isNotEmpty())
          append(
            "<p class=\"muted\">Left out of the design: UI Builder has no state for " +
              unsupported.joinToString {
                "<code>${esc(it.name)}</code> (${it.type.name.lowercase()})"
              } +
              ", so nothing in it can bind them yet.</p>"
          )
      }
    }
  }

  private fun StringBuilder.tile(
    design: LibraryDesign,
    preview: BrowserFilter.Preview,
    compact: Boolean = false,
  ) {
    val theme = ThemeParts.of(design.key)
    val image =
      when (preview) {
        BrowserFilter.Preview.Example -> design.catalog?.previews?.example
        BrowserFilter.Preview.Template -> design.catalog?.previews?.template
      }
    append("<a class=\"tile\" href=\"/designs/${design.id}\">")
    if (image != null)
      append(
        "<img loading=\"lazy\" src=\"$API_BASE/$image\" alt=\"${esc(design.key.cardType)} preview\">"
      )
    else append("<div class=\"noimage\">no preview</div>")
    append("<div class=\"meta\">")
    if (!compact) {
      append("<div class=\"chips\">")
      design.properties.entries.take(6).forEach { (k, v) -> append(chip(k, v)) }
      if (design.properties.size > 6)
        append("<span class=\"muted\">+${design.properties.size - 6}</span>")
      append("</div>")
    }
    append(
      "<div class=\"facts\">${esc(theme.style)}${theme.mode?.let { " · ${esc(it)}" } ?: ""} · ${design.entry.slots} slots · ${kib(design.entry.bytes.toLong())}</div>"
    )
    append("</div></a>")
  }

  private fun StringBuilder.select(
    name: String,
    label: String,
    values: List<String>,
    selected: String?,
  ) {
    append("<label>$label <select name=\"$name\">")
    option(BrowserFilter.ALL, "All", selected == null)
    values.forEach { option(it, it, it == selected) }
    append("</select></label>")
  }

  private fun StringBuilder.option(value: String, label: String, selected: Boolean) {
    append(
      "<option value=\"${esc(value)}\"${if (selected) " selected" else ""}>${esc(label)}</option>"
    )
  }

  private fun StringBuilder.figure(src: String, caption: String) {
    append(
      "<figure><img src=\"$src\" alt=\"${esc(caption)}\"><figcaption>${esc(caption)}</figcaption></figure>"
    )
  }

  private fun StringBuilder.row(label: String, html: String) {
    append("<tr><th>${esc(label)}</th><td>$html</td></tr>")
  }

  private fun chip(name: String, value: String) =
    "<span class=\"chip\"><b>${esc(name)}</b> ${esc(value)}</span>"

  private fun page(title: String, body: StringBuilder.() -> Unit): String = buildString {
    append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>${esc(title)}</title><style>$CSS</style></head><body><main>")
    body()
    append("</main></body></html>")
  }

  private val prettyJson = Json { prettyPrint = true }

  private fun kib(bytes: Long) = if (bytes < 1024) "$bytes B" else "%.1f KiB".format(bytes / 1024.0)

  private fun date(epochMs: Long) =
    Instant.ofEpochMilli(epochMs).toString().take(16).replace('T', ' ') + " UTC"

  private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

  fun esc(value: String): String =
    buildString(value.length) {
      for (c in value) {
        when (c) {
          '&' -> append("&amp;")
          '<' -> append("&lt;")
          '>' -> append("&gt;")
          '"' -> append("&quot;")
          '\'' -> append("&#39;")
          else -> append(c)
        }
      }
    }

  private const val CSS =
    """
:root{--bg:#f6f7f9;--fg:#1b1d21;--muted:#5f6670;--card:#fff;--line:#dfe2e7;--chip:#eef1f5;--accent:#1f6feb;--err:#b42318}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--fg:#e8eaed;--muted:#9aa1ab;--card:#1c1f24;--line:#2c3138;--chip:#262a31;--accent:#6ea8fe;--err:#ff8a80}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:1200px;margin:0 auto;padding:20px 16px 48px}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
h1{margin:0 0 4px;font-size:26px}h2{margin:28px 0 10px;font-size:18px}h2 span{color:var(--muted);font-weight:normal;font-size:14px}
.stats,.hint,.count,.muted{color:var(--muted)}.stats{margin:0}.hint{margin:4px 0 0;font-size:13px}code{font:13px ui-monospace,SFMono-Regular,Menlo,monospace;overflow-wrap:anywhere}
.filters{display:flex;flex-wrap:wrap;gap:10px 14px;align-items:end;margin:18px 0 6px;padding:12px;background:var(--card);border:1px solid var(--line);border-radius:10px}
.filters label{display:flex;flex-direction:column;gap:4px;font-size:12px;color:var(--muted)}select,input,button{font:inherit;padding:6px 8px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
button{background:var(--accent);border-color:var(--accent);color:#fff;cursor:pointer}
button.link{background:none;border:none;color:var(--accent);padding:0;text-decoration:underline}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px;align-items:start}.grid.small{grid-template-columns:repeat(auto-fill,minmax(170px,1fr))}
.tile{display:flex;flex-direction:column;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden;color:inherit}.tile:hover{border-color:var(--accent);text-decoration:none}
.tile img{display:block;width:100%;height:auto;background:repeating-conic-gradient(var(--chip) 0 25%,transparent 0 50%) 0 0/16px 16px}
.noimage{padding:30px;text-align:center;color:var(--muted)}.meta{padding:8px 10px;display:flex;flex-direction:column;gap:6px}.facts{font-size:12px;color:var(--muted)}
.chips{display:flex;flex-wrap:wrap;gap:4px;margin:0}.chip{background:var(--chip);border-radius:999px;padding:1px 8px;font-size:12px;overflow-wrap:anywhere}.chip b{font-weight:600}
.previews{display:flex;flex-wrap:wrap;gap:16px;margin:16px 0}figure{margin:0;max-width:380px;width:100%}figure img{width:100%;border:1px solid var(--line);border-radius:10px;background:repeating-conic-gradient(var(--chip) 0 25%,transparent 0 50%) 0 0/16px 16px}
figcaption{font-size:12px;color:var(--muted);margin-top:4px}table{border-collapse:collapse;width:100%;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}
th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}th{width:220px;color:var(--muted);font-weight:500}
textarea{width:100%;font:13px ui-monospace,SFMono-Regular,Menlo,monospace;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg)}.stack{display:flex;flex-direction:column;gap:8px;align-items:flex-start}
pre{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;overflow:auto;font-size:13px}.error{color:var(--err)}nav{margin-bottom:8px}details{margin-top:10px}
@media (max-width:600px){th{width:auto}.grid{grid-template-columns:1fr}}
"""
}
