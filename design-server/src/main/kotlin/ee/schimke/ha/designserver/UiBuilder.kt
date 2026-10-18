package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Where the design server reaches UI Builder (the authoring surface `compose-preview-server`
 * serves), and how a person's browser does.
 *
 * - [apiUrl]: the server-to-server base, for creating designs.
 * - [browserUrl]: the base the editor links use.
 * - [token]: UI Builder's operator token. It gates the editor (links carry it) and is the design
 *   server's edit token: only a browser that presented it can create designs (see [designServer]).
 */
data class UiBuilderConfig(val apiUrl: String, val browserUrl: String, val token: String) {
  /** The editor for [designId], ready to open: UI Builder reads its operator token from the URL. */
  fun editorUrl(designId: String): String = "${designUrl(designId)}?token=${enc(token)}"

  /** The editor for [designId] without the operator token: for a person signed in to UI Builder. */
  fun designUrl(designId: String): String = "${browserUrl.trimEnd('/')}/ui-builder/${enc(designId)}"

  companion object {
    /**
     * From the environment:
     * - `UI_BUILDER_URL` (+ optional `UI_BUILDER_BROWSER_URL`) and `UI_BUILDER_TOKEN`: a UI Builder
     *   someone else runs.
     * - `UI_BUILDER_BINARY`: run one ([UiBuilderProcess]), with `UI_BUILDER_PORT` (8724),
     *   `UI_BUILDER_HOST` (127.0.0.1), `UI_BUILDER_BROWSER_URL` (`http://localhost:<port>`),
     *   `UI_BUILDER_STATE_DIR`, `UI_BUILDER_TOKEN` (random when unset) and `UI_BUILDER_ARGS`: more
     *   `serve` flags, space-separated — its GitHub sign-in, for one (see design-server's README).
     *
     * Null when neither is set: the design server runs without editing.
     */
    fun fromEnvironment(
      env: Map<String, String> = System.getenv()
    ): Pair<UiBuilderConfig, UiBuilderProcess?>? {
      val binary = env["UI_BUILDER_BINARY"]?.takeIf { it.isNotBlank() }
      if (binary != null) {
        val port = env["UI_BUILDER_PORT"]?.toIntOrNull() ?: 8724
        val host = env["UI_BUILDER_HOST"] ?: "127.0.0.1"
        val token = env["UI_BUILDER_TOKEN"]?.takeIf { it.isNotBlank() } ?: randomToken()
        val process =
          UiBuilderProcess(
            binary = File(binary),
            host = host,
            port = port,
            token = token,
            stateDir = File(env["UI_BUILDER_STATE_DIR"] ?: "ui-builder-state"),
            extraArgs =
              githubArgs(env) +
                env["UI_BUILDER_ARGS"].orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() },
          )
        val config =
          UiBuilderConfig(
            apiUrl = "http://${connectHost(host)}:$port",
            browserUrl = env["UI_BUILDER_BROWSER_URL"] ?: "http://localhost:$port",
            token = token,
          )
        return config to process
      }
      val url = env["UI_BUILDER_URL"]?.takeIf { it.isNotBlank() } ?: return null
      val token =
        requireNotNull(env["UI_BUILDER_TOKEN"]?.takeIf { it.isNotBlank() }) {
          "UI_BUILDER_URL needs UI_BUILDER_TOKEN, the UI Builder's operator token"
        }
      return UiBuilderConfig(url, env["UI_BUILDER_BROWSER_URL"] ?: url, token) to null
    }

    /**
     * The bundled UI Builder's own GitHub sign-in, from `UI_BUILDER_GITHUB_CLIENT_ID`,
     * `UI_BUILDER_GITHUB_CLIENT_SECRET` and `UI_BUILDER_COOKIE_SECRET`, with its callback at
     * `UI_BUILDER_BROWSER_URL`. People who design cards sign in to it as themselves, so it admits
     * any GitHub account (`--github-auth-open-ui-builder`) and keeps each design private to its
     * owner and whoever it is shared with — the design server shares each one with its designer.
     * `UI_BUILDER_GITHUB_REPO` is the repository its sign-in is checked against (this one by
     * default); access to it is not needed to design. Empty when the client id is unset.
     */
    fun githubArgs(env: Map<String, String>): List<String> {
      val clientId =
        env["UI_BUILDER_GITHUB_CLIENT_ID"]?.takeIf { it.isNotBlank() } ?: return emptyList()
      fun required(name: String) =
        requireNotNull(env[name]?.takeIf { it.isNotBlank() }) {
          "UI_BUILDER_GITHUB_CLIENT_ID is set, so $name is needed too"
        }
      return listOf(
        "--github-auth-client-id",
        clientId,
        "--github-auth-client-secret",
        required("UI_BUILDER_GITHUB_CLIENT_SECRET"),
        "--github-auth-cookie-secret",
        required("UI_BUILDER_COOKIE_SECRET"),
        "--github-auth-repo",
        env["UI_BUILDER_GITHUB_REPO"]?.takeIf { it.isNotBlank() }
          ?: "yschimke/homeassistant-remotecompose",
        "--github-auth-callback-base-url",
        required("UI_BUILDER_BROWSER_URL"),
        "--github-auth-open-ui-builder",
        "--ui-builder-default-visibility",
        "private",
      )
    }

    /**
     * Where the design server reaches a child bound to [bindHost]: the address itself, except a
     * wildcard bind (all interfaces), which is reached on loopback. IPv6 literals are bracketed.
     */
    fun connectHost(bindHost: String): String {
      val host = bindHost.trim().removePrefix("[").removeSuffix("]")
      return when {
        host.isEmpty() || host == "0.0.0.0" -> "127.0.0.1"
        host == "::" || host == "0:0:0:0:0:0:0:0" -> "[::1]"
        ':' in host -> "[$host]"
        else -> host
      }
    }

    fun randomToken(): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
  }
}

/**
 * The bundled UI Builder: `compose-preview-server ui` with the packaged catalogs (`--no-project`),
 * a fixed operator token and a state directory, run as a child of the design server and stopped
 * with it.
 */
class UiBuilderProcess(
  private val binary: File,
  private val host: String,
  private val port: Int,
  private val token: String,
  private val stateDir: File,
  private val extraArgs: List<String> = emptyList(),
) {
  private var process: Process? = null

  fun start() {
    stateDir.mkdirs()
    val command =
      listOf(
        binary.absolutePath,
        "ui",
        "--no-project",
        "--no-open",
        "--host",
        host,
        "--port",
        port.toString(),
        "--token",
        token,
        "--ui-builder-state-dir",
        stateDir.absolutePath,
      ) + extraArgs
    process = ProcessBuilder(command).inheritIO().start()
    Runtime.getRuntime().addShutdownHook(Thread { stop() })
  }

  fun stop() {
    process?.let {
      it.destroy()
      if (!it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
    }
    process = null
  }
}

/**
 * Creates UI Builder designs over its REST API (`PUT /api/ui-builder/v1/designs/<id>`) and shares
 * them. [http] must not follow redirects (see [share]).
 */
class UiBuilderClient(private val config: UiBuilderConfig, private val http: HttpClient) {
  /** Creates [document] (it carries its own id) and returns its design id. */
  suspend fun create(document: JsonObject, resolveCatalog: Boolean = false): String {
    val pinned = if (resolveCatalog) pinToServedCatalog(document) else document
    val id = (document["id"] as JsonPrimitive).content
    val response =
      http.put(
        "${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/$id?token=${config.token}"
      ) {
        // The route creates and never replaces: a clash is a refusal, not an overwrite.
        header(HttpHeaders.IfNoneMatch, "*")
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(JsonObject.serializer(), pinned))
      }
    check(response.status.isSuccess()) {
      "UI Builder refused the design (${response.status}): ${response.bodyAsText().take(300)}"
    }
    return id
  }

  /**
   * The Remote Compose document UI Builder compiles [designId] to (`export.rc`), at [revision] or
   * its latest, with the revision it drew. A failure, saying why, when UI Builder has no such
   * design or refuses to compile it.
   */
  suspend fun exportRc(designId: String, revision: Long? = null): Result<ExportedDocument> {
    val id = URLEncoder.encode(designId, Charsets.UTF_8)
    val response =
      http.get(
        "${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/$id/export.rc" +
          "?token=${URLEncoder.encode(config.token, Charsets.UTF_8)}" +
          (revision?.let { "&revision=$it" } ?: "")
      )
    if (!response.status.isSuccess()) {
      return Result.failure(
        IllegalStateException(
          "UI Builder could not export $designId (${response.status}): " +
            response.bodyAsText().take(300)
        )
      )
    }
    return Result.success(
      ExportedDocument(
        bytes = response.bodyAsBytes(),
        revision = response.headers["X-UI-Builder-Revision"]?.toLongOrNull() ?: revision,
      )
    )
  }

  /** Read a committed source with the browser's identity, never borrowing operator read rights. */
  suspend fun source(designId: String, revision: Long?, cookie: String?): JsonObject {
    require(Regex("[a-zA-Z0-9_-]{1,160}").matches(designId))
    val response =
      http.get(
        "${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/$designId" +
          (revision?.let { "?revision=$it" } ?: "")
      ) {
        cookie?.let { header(HttpHeaders.Cookie, it) }
      }
    check(response.status.isSuccess()) { "Cannot read this design as the signed-in admin" }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
  }

  /** Forward only the session cookie to the trusted builder host, never an operator token. */
  suspend fun catalogActor(cookie: String?): String? {
    if (cookie.isNullOrBlank()) return null
    val response =
      http.get("${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/identity") {
        header(HttpHeaders.Cookie, cookie)
      }
    if (!response.status.isSuccess()) return null
    val identity = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return identity["actorId"]?.jsonPrimitive?.content?.takeIf {
      identity["signedIn"] == JsonPrimitive(true) && identity["canWrite"] == JsonPrimitive(true)
    }
  }

  /** The released server bundles the renderer and builder; retain its release with source pins. */
  suspend fun compilerVersion(): String {
    val response = http.get("${config.apiUrl.trimEnd('/')}/version")
    check(response.status.isSuccess()) { "Compiler release identity unavailable" }
    val version =
      Json.parseToJsonElement(response.bodyAsText())
        .jsonObject
        .getValue("version")
        .jsonPrimitive
        .content
    check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) { "A released compiler is required" }
    return version
  }

  /** Compile these exact source bytes. No latest-revision race and no source rewrite/repinning. */
  suspend fun compileSource(source: JsonObject): ByteArray {
    // Native capture compiles the catalog's actual components. It does not depend on the
    // experimental flat-operation lowerer, which cannot represent arbitrary catalog components.
    val response =
      http.post("${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/documents/native-capture") {
        header("X-Compose-Preview-Token", config.token)
        contentType(ContentType.Application.Json)
        setBody(source.toString())
      }
    check(response.status.isSuccess()) {
      "Catalog native capture refused the pinned source (${response.status.value})"
    }
    val captured = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    check(captured["designId"] == source["id"] && captured["revision"] == source["revision"]) {
      "Compiler captured another source revision"
    }
    val location = captured.getValue("documentUrl").jsonPrimitive.content
    // Never follow a compiler-supplied external URL or put credentials in a URL.
    check(Regex("/d/[A-Za-z0-9_-]+").matches(location)) { "Unexpected captured-document location" }
    val blob =
      http.get("${config.apiUrl.trimEnd('/')}$location/raw") {
        header("X-Compose-Preview-Token", config.token)
      }
    check(blob.status.isSuccess()) { "Captured document unavailable" }
    return blob.bodyAsBytes()
  }

  /** Resolve the published revision on the host that will validate and render this draft. */
  private suspend fun pinToServedCatalog(document: JsonObject): JsonObject {
    val system = document.getValue("catalogPin").jsonObject.getValue("systemId")
    val response =
      http.post("${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/requests?token=${config.token}") {
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("schemaVersion", 1)
            put("requestId", UiBuilderConfig.randomToken())
            put("actorId", "operator")
            putJsonObject("request") { put("type", "listCatalogs") }
          }
            .toString()
        )
      }
    check(response.status.isSuccess()) { "UI Builder catalog lookup failed" }
    val payload =
      Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("response").jsonObject
    check(payload["type"] == JsonPrimitive("catalogs")) { "UI Builder did not list catalogs" }
    val benchmark =
      payload
        .getValue("catalogs")
        .jsonArray
        .map { it.jsonObject.getValue("benchmark").jsonObject }
        .single { it["catalogSystemId"] == system }
    val pin = buildJsonObject {
      put("systemId", system)
      put("catalogRevision", benchmark.getValue("catalogRevision").jsonPrimitive)
      // The v1 service uses this frozen digest; its catalog wire shape has no digest field.
      put("capabilityDigest", "candidate")
      put("nativeRuntimeId", benchmark.getValue("nativeRuntimeId").jsonPrimitive)
    }
    return JsonObject(document + ("catalogPin" to pin))
  }

  /** Public catalog metadata for the embedded dashboard editor; never a design or HA credential. */
  suspend fun dashboardCatalog(): JsonObject {
    val response =
      // The requests API takes the operator token as `?token=`; it answers an Authorization
      // header with 401.
      http.post(
        "${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/requests" +
          "?token=${URLEncoder.encode(config.token, Charsets.UTF_8)}"
      ) {
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("schemaVersion", 1)
            put("requestId", UiBuilderConfig.randomToken())
            put("actorId", "operator")
            putJsonObject("request") { put("type", "listCatalogs") }
          }
            .toString()
        )
      }
    check(response.status.isSuccess()) { "Dashboard catalog lookup failed" }
    return Json.parseToJsonElement(response.bodyAsText())
      .jsonObject
      .getValue("response")
      .jsonObject
      .getValue("catalogs")
      .jsonArray
      .map { it.jsonObject }
      .single {
        it.getValue("benchmark").jsonObject["catalogSystemId"] == JsonPrimitive("lovelace")
      }
  }

  /**
   * Shared-host sign-in is verified by the existing builder identity endpoint, without operator
   * credentials.
   */
  suspend fun dashboardPreviewAccess(cookie: String?): JsonObject {
    return try {
      val response =
        http.get("${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/identity") {
          cookie?.let { header(HttpHeaders.Cookie, it) }
          header(HttpHeaders.Referrer, "${config.browserUrl.trimEnd('/')}/studio/dashboard")
        }
      val identity = Json.parseToJsonElement(response.bodyAsText()).jsonObject
      buildJsonObject {
        put(
          "authorized",
          response.status.isSuccess() && identity["signedIn"] == JsonPrimitive(true),
        )
        (identity["signInUrl"] as? JsonPrimitive)
          ?.content
          ?.takeIf { it != "null" }
          ?.let { put("signInPath", it) }
      }
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      buildJsonObject { put("authorized", false) }
    }
  }

  /**
   * Render through harc's published converter preview, with large knobs in POST rather than URLs.
   */
  suspend fun renderDashboardCard(
    card: JsonObject,
    snapshot: JsonObject,
    style: String,
    dark: Boolean,
    browserCookie: String? = null,
    operator: Boolean = true,
  ): ByteArray {
    val response =
      http.post(
        "${config.apiUrl.trimEnd('/')}/homeassistant-remotecompose/render/ee.schimke.ha.previews.LovelaceCardPreviewKt.LovelaceCardPreview.rc"
      ) {
        if (operator) header("X-Compose-Preview-Token", config.token)
        browserCookie?.let { header(HttpHeaders.Cookie, it) }
        contentType(ContentType.Application.Json)
        setBody(
          buildJsonObject {
            put("knob.cardJson", card.toString())
            put("knob.snapshotJson", snapshot.toString())
            put("knob.cardStyle", style)
            put("knob.dark", dark.toString())
          }
            .toString()
        )
      }
    check(response.status.isSuccess()) {
      "Card converter preview unavailable (${response.status.value})"
    }
    return response.bodyAsBytes()
  }

  /**
   * Shares [designId] with [actorId] (`github:<login>`), as an editor or a viewer, through UI
   * Builder's access route. The server creates designs with the operator token, so it owns them and
   * may share them; neither role may share on.
   */
  suspend fun share(designId: String, actorId: String, editor: Boolean) {
    val response =
      http.submitForm(
        "${config.apiUrl.trimEnd('/')}/ui-builder/${URLEncoder.encode(designId, Charsets.UTF_8)}" +
          "/access?token=${URLEncoder.encode(config.token, Charsets.UTF_8)}",
        parameters {
          append("actorId", actorId)
          append("role", if (editor) "editor" else "viewer")
        },
      )
    // The route answers 303 to its access page when the change is made, and renders that page
    // with a notice when it refuses one: so [http] must not follow redirects.
    check(response.status == HttpStatusCode.SeeOther) {
      "UI Builder did not share $designId with $actorId: ${response.bodyAsText().take(300)}"
    }
  }
}

/**
 * A UI Builder design seeded from a [CardSchema]: a Remote Compose document whose state variables
 * are the card's bindings, with their example values, and a starting layout that already reads them
 * — a header for the card's title, and a row per slot with its name and state, and a switch bound
 * to `is_on` for toggleable domains. On a card ([Target.adaptive]) the layout is adaptive: rows are
 * `collapsible-row`s in a `collapsible-column`, so content drops out rather than overflowing as the
 * card shrinks.
 *
 * Which catalog the document is pinned to, the component ids it is written in and the root it sits
 * in are the [Target]'s: [Target.RemoteM3Card], the design server's own seed, is a `remote-m3`
 * (RemoteCompose Material 3) card; [HaCardsSeed] retargets the same layout to the `ha-cards`
 * catalog's dashboard card, Wear widget and launcher widget roots.
 *
 * The designer takes it from there in the editor. Binding names stay the template's binding names,
 * so what they design keeps reading the same values a client pushes.
 */
object UiBuilderSeed {
  const val CATALOG = "remote-m3"
  private const val CATALOG_REVISION = "wear-widget-scaffolds-v1"
  private const val MAX_ROWS = 8

  private val TOGGLEABLE =
    setOf("light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier")

  /** Whether an entity of [domain] has an on/off a switch can show. */
  fun toggleable(domain: String): Boolean = domain in TOGGLEABLE

  /** The type roles the seed sets text in, largest first. */
  enum class TextRole(val m3Style: String) {
    Title("titleLarge"),
    Name("titleMedium"),
    State("bodyMedium"),
    Label("labelMedium"),
  }

  /**
   * Where a seeded document goes: its catalog, the ids of the text and switch it writes, the root
   * it sits in, and how much of the card fits.
   *
   * @param textStyle the properties that set a [TextRole] on [textComponent]: a type role in
   *   `remote-m3`, a size where the text has no theme type scale.
   * @param rootSlot the root's slot the layout goes in.
   * @param adaptive collapsible rows and column (a card) rather than plain ones (a widget, whose
   *   profile has no collapsible layouts).
   * @param maxRows how many slots get a row.
   * @param title whether the card's title gets a header.
   * @param widthDp the environment's width; null is a 360dp card.
   * @param heightDp the environment's height; null grows with the rows.
   * @param template the template marker a published starting design carries; null for a design.
   */
  class Target(
    val systemId: String,
    val catalogRevision: String,
    val textComponent: String,
    val textStyle: (TextRole) -> Map<String, JsonObject>,
    val switchComponent: String?,
    val rootComponent: String,
    val rootSlot: String,
    val rootProperties: Map<String, JsonObject> = emptyMap(),
    val rootModifiers: List<JsonObject> = emptyList(),
    val adaptive: Boolean = true,
    val maxRows: Int = MAX_ROWS,
    val title: Boolean = true,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val template: String? = null,
  ) {
    companion object {
      /** The design server's seed: a `remote-m3` card, text set by type role. */
      val RemoteM3Card =
        Target(
          systemId = CATALOG,
          catalogRevision = CATALOG_REVISION,
          textComponent = "remote-m3/remote-text",
          textStyle = { mapOf("style" to enum(it.m3Style)) },
          switchComponent = "remote-m3/remote-switch-button",
          rootComponent = "remote-m3/remote-card",
          rootSlot = "content",
          rootModifiers = listOf(fillMaxWidth()),
        )
    }
  }

  fun document(schema: CardSchema, designId: String, title: String): JsonObject =
    document(schema, designId, title, Target.RemoteM3Card)

  fun document(schema: CardSchema, designId: String, title: String, target: Target): JsonObject {
    val byName = schema.bindings.associateBy { it.name }
    val nodes = LinkedHashMap<String, JsonObject>()
    val children = mutableListOf<String>()

    val titleBinding =
      listOf("card.title", "card.name").firstOrNull { it in byName }.takeIf { target.title }
    if (titleBinding != null) {
      nodes["title"] = text(target, "title", titleBinding, TextRole.Title)
      children += "title"
    }
    for (slot in schema.slots.take(target.maxRows)) {
      val i = slot.index
      val name = "${slot.slot}.name".takeIf { it in byName }
      val state = "${slot.slot}.state".takeIf { it in byName }
      val rowChildren = mutableListOf<String>()
      val texts =
        listOfNotNull(
          name?.let { "name$i" to text(target, "name$i", it, TextRole.Name) },
          state?.let { "state$i" to text(target, "state$i", it, TextRole.State) },
        )
      if (texts.isNotEmpty()) {
        texts.forEach { (id, node) -> nodes[id] = node }
        nodes["text$i"] =
          node(
            "text$i",
            "layout/column",
            properties = mapOf("weight" to float(1.0)),
            slots = mapOf("children" to texts.map { it.first }),
          )
        rowChildren += "text$i"
      }
      val isOn = "${slot.slot}.is_on"
      val switch = target.switchComponent
      if (switch != null && toggleable(slot.domain) && byName[isOn]?.type == BindingType.Bool) {
        // The switch's label is the state text. A template that only binds `is_on` leaves it
        // unlabelled: the row already shows the name, and the boolean itself is no label.
        val label = state
        if (label != null)
          nodes["switch-label$i"] = text(target, "switch-label$i", label, TextRole.Label)
        nodes["switch$i"] =
          node(
            "switch$i",
            switch,
            properties = mapOf("checked" to stateValue(isOn)),
            slots =
              mapOf(
                "label" to listOfNotNull("switch-label$i".takeIf { label != null }),
                "icon" to emptyList(),
                "secondaryLabel" to emptyList(),
              ),
          )
        rowChildren += "switch$i"
      }
      if (rowChildren.isEmpty()) continue
      nodes["row$i"] =
        node(
          "row$i",
          if (target.adaptive) "layout/collapsible-row" else "layout/row",
          properties =
            mapOf("horizontalSpacingDp" to float(12.0), "verticalAlignment" to enum("center")),
          modifiers = listOf(fillMaxWidth()),
          slots = mapOf("children" to rowChildren),
        )
      children += "row$i"
    }
    nodes["column"] =
      node(
        "column",
        if (target.adaptive) "layout/collapsible-column" else "layout/column",
        properties = mapOf("verticalSpacingDp" to float(if (target.adaptive) 8.0 else 4.0)),
        modifiers = listOf(fillMaxWidth()),
        slots = mapOf("children" to children),
      )
    nodes["root"] =
      node(
        "root",
        target.rootComponent,
        properties = target.rootProperties,
        modifiers = target.rootModifiers,
        slots = mapOf(target.rootSlot to listOf("column")),
      )

    return envelope(
      target,
      designId,
      title,
      heightDp = target.heightDp ?: (96 + 64 * (children.size.coerceAtLeast(1))),
      stateVariables = stateVariables(schema),
      nodes = nodes,
    )
  }

  /**
   * A document pinned to [target]'s catalog, holding [nodes] under the single root `root`: what
   * every seeded design shares, whatever its layout.
   */
  fun envelope(
    target: Target,
    designId: String,
    title: String,
    heightDp: Int,
    stateVariables: List<Pair<String, JsonObject>>,
    nodes: Map<String, JsonObject>,
  ): JsonObject = buildJsonObject {
    put("schema", "compose-ui-builder-document/v1-candidate")
    put("id", designId)
    put("title", title)
    put("revision", 0)
    target.template?.let { put("template", it) }
    putJsonObject("catalogPin") {
      put("systemId", target.systemId)
      put("catalogRevision", target.catalogRevision)
      put("capabilityDigest", "candidate")
      put("nativeRuntimeId", "candidate")
    }
    putJsonObject("environment") {
      put("widthDp", target.widthDp ?: 360)
      put("heightDp", heightDp)
      put("density", 2)
      put("theme", "light")
      put("locale", "en-US")
      put("fontScale", 1)
      put("layoutDirection", "ltr")
    }
    putJsonObject("stateVariables") { stateVariables.forEach { (name, v) -> put(name, v) } }
    putJsonArray("roots") { add(JsonPrimitive("root")) }
    putJsonObject("nodes") { nodes.forEach { (id, node) -> put(id, node) } }
  }

  /** A path-safe design id for [schema], unique per call. */
  fun designId(schema: CardSchema): String {
    val type = schema.cardType.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
    return "card-$type-${System.currentTimeMillis().toString(36)}"
  }

  /** Whether UI Builder has a state type for [type]: colours and images have none yet. */
  fun supports(type: BindingType): Boolean = type != BindingType.Color && type != BindingType.Image

  /**
   * The bindings of [schema] a seeded design leaves out, because UI Builder has no state type for
   * them. Shown beside "Design in UI Builder" so a designer knows before they start, rather than
   * finding a binding missing from the design.
   */
  fun unsupported(schema: CardSchema): List<BindingSchema> =
    schema.bindings.filterNot { supports(it.type) }

  /** A binding as a UI Builder state variable; null for kinds it has no state type for. */
  /** [schema]'s bindings as UI Builder state variables, those UI Builder has a type for. */
  fun stateVariables(schema: CardSchema): List<Pair<String, JsonObject>> =
    schema.bindings.mapNotNull { b -> stateVariable(b)?.let { b.name to it } }

  private fun stateVariable(binding: BindingSchema): JsonObject? {
    val (type, valueType, default) =
      when (binding.type) {
        BindingType.Text -> Triple("text", "string", JsonPrimitive(""))
        BindingType.Bool -> Triple("value", "bool", JsonPrimitive(false))
        BindingType.Int -> Triple("value", "int", JsonPrimitive(0))
        BindingType.Float -> Triple("value", "float", JsonPrimitive(0.0))
        BindingType.Color,
        BindingType.Image -> return null
      }
    return buildJsonObject {
      put("type", type)
      put("valueType", valueType)
      put("initialValue", binding.example ?: default)
      put("persistence", "design")
    }
  }

  private fun text(target: Target, id: String, variable: String, role: TextRole) =
    node(
      id,
      target.textComponent,
      properties =
        mapOf("text" to stateValue(variable)) + target.textStyle(role) + ("maxLines" to int(1)),
    )

  fun node(
    id: String,
    componentId: String,
    properties: Map<String, JsonObject> = emptyMap(),
    modifiers: List<JsonObject> = emptyList(),
    slots: Map<String, List<String>> = emptyMap(),
  ) = buildJsonObject {
    put("id", id)
    put("componentId", componentId)
    putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
    put("modifiers", JsonArray(modifiers))
    putJsonObject("slots") {
      slots.forEach { (k, v) -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }) }
    }
  }

  fun stateValue(variable: String) = buildJsonObject {
    put("type", "state")
    put("variable", variable)
  }

  fun string(value: String) = buildJsonObject {
    put("type", "string")
    put("value", value)
  }

  fun enum(value: String) = buildJsonObject {
    put("type", "enum")
    put("value", value)
  }

  fun float(value: Double) = buildJsonObject {
    put("type", "float")
    put("value", value)
  }

  fun int(value: Int) = buildJsonObject {
    put("type", "int")
    put("value", value)
  }

  fun fillMaxWidth() = buildJsonObject { put("type", "fillMaxWidth") }
}

/** A design's Remote Compose bytes as UI Builder exported them, and the revision they are of. */
class ExportedDocument(val bytes: ByteArray, val revision: Long?)
