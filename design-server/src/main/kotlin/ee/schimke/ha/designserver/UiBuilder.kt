package ee.schimke.ha.designserver

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
  fun editorUrl(designId: String): String =
    "${browserUrl.trimEnd('/')}/ui-builder/${enc(designId)}?token=${enc(token)}"

  companion object {
    /**
     * From the environment:
     * - `UI_BUILDER_URL` (+ optional `UI_BUILDER_BROWSER_URL`) and `UI_BUILDER_TOKEN`: a UI Builder
     *   someone else runs.
     * - `UI_BUILDER_BINARY`: run one ([UiBuilderProcess]), with `UI_BUILDER_PORT` (8724),
     *   `UI_BUILDER_HOST` (127.0.0.1), `UI_BUILDER_BROWSER_URL` (`http://localhost:<port>`),
     *   `UI_BUILDER_STATE_DIR` and `UI_BUILDER_TOKEN` (random when unset).
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
      )
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

/** Creates UI Builder designs over its REST API (`PUT /api/ui-builder/v1/designs/<id>`). */
class UiBuilderClient(private val config: UiBuilderConfig, private val http: HttpClient) {
  /** Creates [document] (it carries its own id) and returns the editor URL for it. */
  suspend fun create(document: JsonObject): String {
    val id = (document["id"] as JsonPrimitive).content
    val response =
      http.put(
        "${config.apiUrl.trimEnd('/')}/api/ui-builder/v1/designs/$id?token=${config.token}"
      ) {
        // The route creates and never replaces: a clash is a refusal, not an overwrite.
        header(HttpHeaders.IfNoneMatch, "*")
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(JsonObject.serializer(), document))
      }
    check(response.status.isSuccess()) {
      "UI Builder refused the design (${response.status}): ${response.bodyAsText().take(300)}"
    }
    return config.editorUrl(id)
  }
}

/**
 * A UI Builder design seeded from a [CardSchema]: a `remote-m3` (RemoteCompose Material 3) document
 * whose state variables are the card's bindings, with their example values, and a starting layout
 * that already reads them — a header for the card's title, and a row per slot with its name and
 * state, and a switch bound to `is_on` for toggleable domains. The layout is adaptive: rows are
 * `collapsible-row`s in a `collapsible-column`, so content drops out rather than overflowing as the
 * card shrinks.
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

  fun document(schema: CardSchema, designId: String, title: String): JsonObject {
    val byName = schema.bindings.associateBy { it.name }
    val nodes = LinkedHashMap<String, JsonObject>()
    val children = mutableListOf<String>()

    val titleBinding = listOf("card.title", "card.name").firstOrNull { it in byName }
    if (titleBinding != null) {
      nodes["title"] = text("title", titleBinding, "titleLarge")
      children += "title"
    }
    for (slot in schema.slots.take(MAX_ROWS)) {
      val i = slot.index
      val name = "${slot.slot}.name".takeIf { it in byName }
      val state = "${slot.slot}.state".takeIf { it in byName }
      val rowChildren = mutableListOf<String>()
      val texts =
        listOfNotNull(
          name?.let { "name$i" to text("name$i", it, "titleMedium") },
          state?.let { "state$i" to text("state$i", it, "bodyMedium") },
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
      if (slot.domain in TOGGLEABLE && byName[isOn]?.type == BindingType.Bool) {
        // The switch's label is the state text. A template that only binds `is_on` leaves it
        // unlabelled: the row already shows the name, and the boolean itself is no label.
        val label = state
        if (label != null) nodes["switch-label$i"] = text("switch-label$i", label, "labelMedium")
        nodes["switch$i"] =
          node(
            "switch$i",
            "remote-m3/remote-switch-button",
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
          "layout/collapsible-row",
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
        "layout/collapsible-column",
        properties = mapOf("verticalSpacingDp" to float(8.0)),
        modifiers = listOf(fillMaxWidth()),
        slots = mapOf("children" to children),
      )
    nodes["root"] =
      node(
        "root",
        "remote-m3/remote-card",
        modifiers = listOf(fillMaxWidth()),
        slots = mapOf("content" to listOf("column")),
      )

    return buildJsonObject {
      put("schema", "compose-ui-builder-document/v1-candidate")
      put("id", designId)
      put("title", title)
      put("revision", 0)
      putJsonObject("catalogPin") {
        put("systemId", CATALOG)
        put("catalogRevision", CATALOG_REVISION)
        put("capabilityDigest", "candidate")
        put("nativeRuntimeId", "candidate")
      }
      putJsonObject("environment") {
        put("widthDp", 360)
        put("heightDp", 96 + 64 * (children.size.coerceAtLeast(1)))
        put("density", 2)
        put("theme", "light")
        put("locale", "en-US")
        put("fontScale", 1)
        put("layoutDirection", "ltr")
      }
      putJsonObject("stateVariables") {
        for (binding in schema.bindings) stateVariable(binding)?.let { put(binding.name, it) }
      }
      putJsonArray("roots") { add(JsonPrimitive("root")) }
      putJsonObject("nodes") { nodes.forEach { (id, node) -> put(id, node) } }
    }
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

  private fun text(id: String, variable: String, style: String) =
    node(
      id,
      "remote-m3/remote-text",
      properties =
        mapOf("text" to stateValue(variable), "style" to enum(style), "maxLines" to int(1)),
    )

  private fun node(
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

  private fun stateValue(variable: String) = buildJsonObject {
    put("type", "state")
    put("variable", variable)
  }

  private fun enum(value: String) = buildJsonObject {
    put("type", "enum")
    put("value", value)
  }

  private fun float(value: Double) = buildJsonObject {
    put("type", "float")
    put("value", value)
  }

  private fun int(value: Int) = buildJsonObject {
    put("type", "int")
    put("value", value)
  }

  private fun fillMaxWidth() = buildJsonObject { put("type", "fillMaxWidth") }
}
