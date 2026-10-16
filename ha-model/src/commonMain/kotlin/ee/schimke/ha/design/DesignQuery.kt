package ee.schimke.ha.design

import kotlinx.serialization.Serializable

/**
 * A choice of designs by tag: those whose card type, card style, variant and author match, narrowed
 * by surface, shape or theme when the exact key is known. Unset fields match anything; tags compare
 * without case. A client filters the whole [DesignManifest] with it — the server never learns which
 * designs a client wanted — and downloads only the blobs its choice matches.
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
  /**
   * A device to find designs for ("designs for my boiler"): when any of its fields is set, only
   * device-specific designs whose [DesignEntry.device] matches it are chosen.
   */
  val device: DeviceInfo? = null,
) {
  fun matches(entry: DesignEntry): Boolean {
    val key = entry.key
    val forDevice = device?.copy(displayPrecision = null)?.takeIf { it != DeviceInfo() }
    return (forDevice == null || entry.device?.matches(forDevice) == true) &&
      tag(cardType, key.cardType) &&
      tag(style, key.cardStyle) &&
      tag(variant, key.variant) &&
      tag(author, entry.author) &&
      (surface == null || surface == key.surface) &&
      (shape == null || shape == key.shape) &&
      (theme == null || theme == key.theme)
  }

  /**
   * The choice as `name=value` pairs (a setting, a link), in a stable order; unset ones left out.
   */
  fun parameters(): List<Pair<String, String>> =
    listOfNotNull(
      cardType?.let { CARD_TYPE to it },
      style?.let { STYLE to it },
      variant?.let { VARIANT to it },
      author?.let { AUTHOR to it },
      surface?.let { SURFACE to it.wire },
      shape?.let { SHAPE to it },
      theme?.let { THEME to it },
      device?.integration?.let { INTEGRATION to it },
      device?.manufacturer?.let { MANUFACTURER to it },
      device?.model?.let { MODEL to it },
      device?.translationKey?.let { TRANSLATION_KEY to it },
    )

  companion object {
    const val CARD_TYPE = "cardType"
    const val STYLE = "style"
    const val VARIANT = "variant"
    const val AUTHOR = "author"
    const val SURFACE = "surface"
    const val SHAPE = "shape"
    const val THEME = "theme"
    const val INTEGRATION = "integration"
    const val MANUFACTURER = "manufacturer"
    const val MODEL = "model"
    const val TRANSLATION_KEY = "translationKey"

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
        device =
          DeviceInfo(
              integration = value(INTEGRATION),
              manufacturer = value(MANUFACTURER),
              model = value(MODEL),
              translationKey = value(TRANSLATION_KEY),
            )
            .takeIf { it != DeviceInfo() },
      )
    }

    private fun tag(wanted: String?, actual: String) =
      wanted == null || wanted.equals(actual, ignoreCase = true)
  }
}

/**
 * Every value each tag takes across a manifest, with how many designs carry it: what a picker
 * offers, worked out on the client from the manifest it already has.
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
