package ee.schimke.ha.design

import kotlinx.serialization.Serializable

/**
 * A lookup by tag: the designs whose card type, card style, variant and author match, narrowed by
 * surface, shape or theme when a client knows its exact key. Unset fields match anything; tags
 * compare without case. What `GET /designs/v1/designs` takes as query parameters, so a client can
 * fetch the designs it needs instead of the whole [DesignManifest].
 */
@Serializable
data class DesignQuery(
  val cardType: String? = null,
  /** A card style (uber theme id): [DesignKey.cardStyle]. */
  val style: String? = null,
  val variant: String? = null,
  val author: String? = null,
  val surface: DesignSurface? = null,
  val shape: String? = null,
  val theme: String? = null,
) {
  fun matches(entry: DesignEntry): Boolean {
    val key = entry.key
    return tag(cardType, key.cardType) &&
      tag(style, key.cardStyle) &&
      tag(variant, key.variant) &&
      tag(author, entry.author) &&
      (surface == null || surface == key.surface) &&
      (shape == null || shape == key.shape) &&
      (theme == null || theme == key.theme)
  }

  /** The query as `name=value` pairs, in a stable order: unset fields are left out. */
  fun parameters(): List<Pair<String, String>> =
    listOfNotNull(
      cardType?.let { CARD_TYPE to it },
      style?.let { STYLE to it },
      variant?.let { VARIANT to it },
      author?.let { AUTHOR to it },
      surface?.let { SURFACE to it.wire },
      shape?.let { SHAPE to it },
      theme?.let { THEME to it },
    )

  companion object {
    const val CARD_TYPE = "cardType"
    const val STYLE = "style"
    const val VARIANT = "variant"
    const val AUTHOR = "author"
    const val SURFACE = "surface"
    const val SHAPE = "shape"
    const val THEME = "theme"

    /**
     * The query [parameter] reads, or null when a value is unusable (an unknown surface): an
     * unusable query matches nothing rather than everything. Blank values are unset.
     */
    fun of(parameter: (String) -> String?): DesignQuery? {
      fun value(name: String) = parameter(name)?.trim()?.takeIf { it.isNotEmpty() }
      val surface =
        value(SURFACE)?.let { wire ->
          DesignSurface.entries.firstOrNull { it.wire == wire } ?: return null
        }
      return DesignQuery(
        cardType = value(CARD_TYPE),
        style = value(STYLE),
        variant = value(VARIANT),
        author = value(AUTHOR),
        surface = surface,
        shape = value(SHAPE),
        theme = value(THEME),
      )
    }

    private fun tag(wanted: String?, actual: String) =
      wanted == null || wanted.equals(actual, ignoreCase = true)
  }
}

/** What `GET /designs/v1/designs` answers: the matching designs, and every tag the library has. */
@Serializable
data class DesignQueryResult(
  /** The [DesignManifest.serial] of the library that answered. */
  val serial: Long,
  val designs: List<DesignEntry>,
  val tags: DesignTags,
)

/**
 * Every value each tag takes across a library, with how many designs carry it: what a picker
 * offers. Counted over the whole library, not the query, so a narrowed lookup still lists them.
 */
@Serializable
data class DesignTags(
  val cardType: Map<String, Int> = emptyMap(),
  val style: Map<String, Int> = emptyMap(),
  val variant: Map<String, Int> = emptyMap(),
  val author: Map<String, Int> = emptyMap(),
) {
  companion object {
    fun of(designs: List<DesignEntry>): DesignTags {
      fun count(tag: (DesignEntry) -> String) =
        designs
          .groupingBy(tag)
          .eachCount()
          .entries
          .sortedBy { it.key }
          .associate { it.key to it.value }
      return DesignTags(
        cardType = count { it.key.cardType },
        style = count { it.key.cardStyle },
        variant = count { it.key.variant },
        author = count { it.author },
      )
    }
  }
}
