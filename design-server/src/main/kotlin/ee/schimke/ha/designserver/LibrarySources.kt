package ee.schimke.ha.designserver

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The library's card designs, as UI Builder designs the people who own the library edit
 * (server-owned-card-designs.md, "The pipeline"):
 * - Each migrated card type's checked-in design (`DesignLibrary.sources`) is edited as the UI
 *   Builder design [designId]: created from the checked-in source the first time someone opens it,
 *   edited in place after that.
 * - The current source of each, the edited one when it exists, else the checked-in one, is what the
 *   scheduled import copies back into the repository: `GET /api/library/sources` (all of them) and
 *   `GET /api/library/sources/<cardType>.uid` (one). They are public, like the library itself.
 * - `POST /library/<cardType>/edit` opens one in UI Builder, for [editors] only.
 */
class LibrarySources(
  private val holder: LibraryHolder,
  private val uiBuilder: UiBuilderIntegration?,
  /**
   * GitHub logins that may edit the library. Without GitHub sign-in, whoever holds the edit link
   * may; with it and none named, nobody may.
   */
  private val editors: Set<String> = emptySet(),
) {
  /**
   * A card type's current source: the edited design, when there is one, else the checked-in. Throws
   * when UI Builder can't be reached, rather than answer with the checked-in source: an import that
   * read that would quietly drop every edit.
   */
  suspend fun current(cardType: String): Source? {
    val checkedIn = holder.current().sources[cardType] ?: return null
    val edited = uiBuilder?.client?.design(designId(cardType))?.getOrNull()
    return if (edited == null) Source(cardType, checkedIn, edited = false)
    else Source(cardType, normalized(edited, cardType), edited = true)
  }

  /** Every migrated card type's current source, by card type. */
  suspend fun all(): List<Source> = holder.current().sources.keys.mapNotNull { current(it) }

  /** Whether [editor] may edit the library. */
  fun mayEdit(editor: EditorState): Boolean =
    editor.authorized &&
      (editor.login == null || editors.any { it.equals(editor.login, ignoreCase = true) })

  /** One source: a card type's design document and whether it has been edited in UI Builder. */
  class Source(val cardType: String, val document: JsonObject, val edited: Boolean) {
    val revision: Long?
      get() = (document["revision"] as? JsonPrimitive)?.longOrNull
  }

  companion object {
    /** The UI Builder design a card type's library design is edited as. */
    fun designId(cardType: String): String =
      "library-" + cardType.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * [document] as the repository keeps it: named for its card type (the file name), so an edit in
     * UI Builder imports as a change to that file and nothing else.
     */
    fun normalized(document: JsonObject, cardType: String): JsonObject =
      JsonObject(document + ("id" to JsonPrimitive(cardType)))

    /**
     * [source] as the start of a fork of the library [design]: the real design, as a new design (UI
     * Builder creates at revision 0). The fork is a copy: it doesn't follow later changes to the
     * library design. Where it came from is recorded when it is published (`sourceDesignId`), since
     * a UI Builder document has no field for it.
     */
    fun forkOf(source: JsonObject, design: LibraryDesign): JsonObject =
      JsonObject(
        source +
          mapOf(
            "title" to JsonPrimitive("${design.key.cardType} (fork)"),
            "revision" to JsonPrimitive(0),
          )
      )

    /** The checked-in [source] as a new UI Builder design for its card type. */
    fun forEditing(source: JsonObject, cardType: String): JsonObject =
      JsonObject(
        source +
          mapOf(
            "id" to JsonPrimitive(designId(cardType)),
            "title" to JsonPrimitive("Library ? $cardType"),
            "revision" to JsonPrimitive(0),
          )
      )
  }
}

private val sourceJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
}

/** The routes [LibrarySources] describes. */
internal fun Route.librarySourceRoutes(
  sources: LibrarySources,
  uiBuilder: UiBuilderIntegration?,
  github: GithubAuth?,
) {
  get("/api/library/sources") {
    val all = runCatching {
      sources.all()
    }
      .getOrElse {
        return@get call.respondText(
          "UI Builder is unreachable: ${it.message}",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
    call.respondText(
      sourceJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
          put(
            "sources",
            buildJsonArray {
              for (source in all) {
                add(
                  buildJsonObject {
                    put("cardType", source.cardType)
                    put("edited", source.edited)
                    source.revision?.let { put("revision", it) }
                    put("document", source.document)
                  }
                )
              }
            },
          )
        },
      ),
      ContentType.Application.Json,
    )
  }

  get("/api/library/sources/{name}") {
    val name = call.parameters["name"].orEmpty()
    if (!name.endsWith(".uid")) return@get call.respondText("", status = HttpStatusCode.NotFound)
    val source =
      runCatching { sources.current(name.removeSuffix(".uid")) }
        .getOrElse {
          return@get call.respondText(
            "UI Builder is unreachable: ${it.message}",
            status = HttpStatusCode.ServiceUnavailable,
          )
        } ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
    call.response.header(
      HttpHeaders.ContentDisposition,
      ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name)
        .toString(),
    )
    call.respondText(
      sourceJson.encodeToString(JsonObject.serializer(), source.document) + "\n",
      ContentType.Application.Json,
    )
  }

  post("/library/{cardType}/edit") {
    val integration =
      uiBuilder ?: return@post call.respondText("", status = HttpStatusCode.NotFound)
    val editor = call.editor(uiBuilder, github)
    if (!sources.mayEdit(editor)) {
      return@post call.respondText(
        "only the library's editors may edit library designs",
        status = HttpStatusCode.Forbidden,
      )
    }
    val cardType = call.parameters["cardType"].orEmpty()
    val source =
      runCatching { sources.current(cardType) }
        .getOrElse {
          return@post call.respondText(
            "UI Builder: ${it.message}",
            status = HttpStatusCode.BadGateway,
          )
        } ?: return@post call.respondText("", status = HttpStatusCode.NotFound)
    val designId = LibrarySources.designId(cardType)
    val location = runCatching {
      // The first edit creates the library design from the checked-in source; later ones open it.
      if (!source.edited) {
        integration.client.create(
          LibrarySources.forEditing(source.document, cardType),
          resolveCatalog = true,
        )
      }
      val login = editor.login
      if (login == null) integration.config.editorUrl(designId)
      else {
        integration.client.share(designId, "github:$login", editor = true)
        integration.config.designUrl(designId)
      }
    }
      .getOrElse {
        return@post call.respondText(
          "UI Builder: ${it.message}",
          status = HttpStatusCode.BadGateway,
        )
      }
    call.respondRedirect(location)
  }
}
