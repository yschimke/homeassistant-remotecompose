package ee.schimke.ha.design

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The browsable side of a design library, published next to the [DesignManifest]: for each design,
 * a card it was generated from and its preview renders. Clients never read it; the design server's
 * template browser does. Like the designs themselves it holds no user's values — the examples are
 * the library's own demo fixtures.
 */
@Serializable
data class DesignCatalog(
  /** The [DesignManifest.serial] this catalog describes. */
  val serial: Long,
  val generatedAtEpochMs: Long,
  val entries: List<DesignCatalogEntry> = emptyList(),
) {
  companion object {
    const val PATH: String = "catalog.json"

    /** Library-relative path of a preview image for the entry with [id]. */
    fun previewPath(id: String, kind: String): String = "previews/$id-$kind.png"
  }
}

@Serializable
data class DesignCatalogEntry(
  /**
   * Stable id of [key] within the library (keys can share a blob, so [sha256] isn't one): the
   * browser's URL for this design.
   */
  val id: String,
  /** The design: [DesignEntry.sha256] in the manifest. */
  val sha256: String,
  val key: DesignKey,
  /** A card whose generation properties give this design's shape, and where it came from. */
  val example: DesignExample? = null,
  /** Library-relative preview images ([DesignCatalog.previewPath]). */
  val previews: DesignPreviews = DesignPreviews(),
)

@Serializable
data class DesignExample(
  /** Where the card came from, e.g. `dashboard:energy`. */
  val source: String,
  /** The card's Lovelace config. */
  val card: JsonObject,
  /** The values the design is bound to for this card, by binding name: what its preview shows. */
  val bindings: Map<String, JsonPrimitive> = emptyMap(),
)

@Serializable
data class DesignPreviews(
  /** The design bound to [DesignExample]'s values: what a user sees. */
  val example: String? = null,
  /** The design as published: its placeholder values, no bindings pushed. */
  val template: String? = null,
)
