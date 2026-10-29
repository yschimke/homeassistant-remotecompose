package ee.schimke.ha.designserver

import ee.schimke.ha.client.design.Sha256
import java.util.concurrent.ConcurrentHashMap

/**
 * How a channel's library (a pull request's preview) differs from the published one it would
 * replace, design by design ([DesignLibrary.designFor] on the same key): what the browser's
 * "Changes" filter and before/after view read.
 *
 * A design is [Change.Picture] when one of its preview images differs (the output looks different),
 * [Change.Document] when only its `.rc` document does (the same picture: an animation, a binding or
 * an encoding changed), [Change.Added] when the published library has no design for its key, and
 * [Change.Unchanged] otherwise. Designs only the published library has are [removed].
 */
class LibraryDiff(val library: DesignLibrary, val base: DesignLibrary) {
  enum class Change(val label: String) {
    Added("new"),
    Picture("picture changed"),
    Document("document changed"),
    Unchanged("unchanged"),
  }

  /** The published design with [design]'s key, or null when it is new. */
  fun before(design: LibraryDesign): LibraryDesign? = base.designFor(design.key)

  private val changes: Map<String, Change> =
    library.designs.associate { design -> design.id to classify(design) }

  fun of(design: LibraryDesign): Change = changes[design.id] ?: Change.Unchanged

  /** Published designs this library has no design for: what merging it would take away. */
  val removed: List<LibraryDesign> =
    base.designs.filter { !it.entry.revoked && library.designFor(it.key) == null }

  /** How many of this library's designs fall in each [Change]. */
  val counts: Map<Change, Int> = changes.values.groupingBy { it }.eachCount()

  private fun classify(design: LibraryDesign): Change {
    val before = before(design) ?: return Change.Added
    val pictures = { d: LibraryDesign, lib: DesignLibrary ->
      listOf(d.catalog?.previews?.example, d.catalog?.previews?.template).map { path ->
        path?.let { lib.preview(it) }?.let(Sha256::hex)
      }
    }
    return when {
      pictures(design, library) != pictures(before, base) -> Change.Picture
      design.entry.sha256 != before.entry.sha256 -> Change.Document
      else -> Change.Unchanged
    }
  }

  companion object {
    /**
     * The diff of [library] against [base], computed once per pair of library versions: it reads
     * every preview image, so a page view mustn't. Keeps the few most recent pairs.
     */
    fun of(library: DesignLibrary, base: DesignLibrary): LibraryDiff {
      val key = library.manifestEtag + base.manifestEtag
      cache[key]?.let {
        return it
      }
      val diff = LibraryDiff(library, base)
      if (cache.size >= CACHED_PAIRS) cache.keys.firstOrNull()?.let(cache::remove)
      cache[key] = diff
      return diff
    }

    private const val CACHED_PAIRS = 8
    private val cache = ConcurrentHashMap<String, LibraryDiff>()
  }
}
