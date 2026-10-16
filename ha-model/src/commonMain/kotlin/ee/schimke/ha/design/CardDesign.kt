package ee.schimke.ha.design

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Card design server — wire model shared by the template builder, the client repository and any
 * server that hosts the files. See `docs/architecture/card-design-server.md`.
 *
 * A *design* is a RemoteCompose document with named bindings but no Home Assistant values. The
 * server only ever knows designs and binding names, never a user's entities.
 *
 * **Spike status.** This is the first end-to-end slice: one card type (`tile`), designs keyed by a
 * baked theme (the `theme.*` colour bindings are spike S2), no signature check beyond what
 * [DesignManifest.signature] reserves, and no inbuilt designs bundled in the app. The client is off
 * unless a design server URL is configured.
 */

/** Which kind of host a design is built for. Wire names are stable; do not localise. */
@Serializable
enum class DesignSurface(val wire: String) {
  @SerialName("mobile-app") MobileApp("mobile-app"),
  @SerialName("launcher") Launcher("launcher"),
  @SerialName("wear-widget") WearWidget("wear-widget"),
  @SerialName("wear-app") WearApp("wear-app"),
}

/**
 * Everything that picks one design document. Two cards that resolve to equal keys can share bytes.
 *
 * @property shape the card's generation properties ([DesignProperties.shape]). Never a value.
 * @property theme how colours are resolved. Classic cards are baked in an app palette
 *   (`baked:<ThemeStyle>:<light|dark>`); a card style with its own palette is `style:<id>:<mode>`
 *   ([styledTheme]), since the app palette doesn't change it. `host` is reserved for designs whose
 *   colours come from `theme.*` bindings, `ColorTheme` or `WearM3`.
 */
@Serializable
data class DesignKey(
  val cardType: String,
  val variant: String = DEFAULT_VARIANT,
  val surface: DesignSurface,
  val rcApiLevel: Int,
  val shape: String,
  val theme: String,
  val schema: Int = BINDING_SCHEMA_VERSION,
) {
  /**
   * The card style (uber theme id) this design is drawn in: the `style` tag. [CLASSIC_STYLE] for a
   * design baked in an app palette. A legacy `baked:<palette>:<mode>:<id>` theme names it last.
   */
  val cardStyle: String
    get() {
      val parts = theme.split(':')
      return when {
        parts.size == 3 && parts[0] == STYLED -> parts[1]
        parts.size == 4 && parts[0] == BAKED -> parts[3]
        else -> CLASSIC_STYLE
      }
    }

  companion object {
    const val DEFAULT_VARIANT: String = "default"
    const val BINDING_SCHEMA_VERSION: Int = 1

    /** The card style of every design baked in an app palette. */
    const val CLASSIC_STYLE: String = "classic"

    private const val BAKED = "baked"
    private const val STYLED = "style"

    fun bakedTheme(style: String, dark: Boolean): String =
      "$BAKED:$style:${if (dark) "dark" else "light"}"

    /** The theme of a design in card style [cardStyle], which brings its own palette. */
    fun styledTheme(cardStyle: String, dark: Boolean): String =
      "$STYLED:$cardStyle:${if (dark) "dark" else "light"}"
  }
}

/** One design in a [DesignManifest]: its key, and the content-addressed blob that holds it. */
@Serializable
data class DesignEntry(
  val key: DesignKey,
  val sha256: String,
  val bytes: Int,
  /** Slot count the template binds (`<domain>.s0` … `<domain>.s<n-1>`). */
  val slots: Int,
  val revoked: Boolean = false,
  /**
   * Who made the design: [BUILTIN_AUTHOR] for the library generated from the app's own converters,
   * a person's id (`github:<login>`) for one published from UI Builder. The `author` tag.
   */
  val author: String = BUILTIN_AUTHOR,
  /**
   * The devices this design is made for (a boiler's flow temperature), or null for a generic
   * design. A client prefers the most specific matching design for a card's entity's device over
   * the generic one with the same [key]; see [DeviceMatch.pick].
   */
  val device: DeviceMatch? = null,
) {
  companion object {
    const val BUILTIN_AUTHOR: String = "terrazzo"
  }
}

/**
 * The index a client fetches. Blobs live at `blobs/<sha256>.rc` next to it and never change.
 *
 * @property serial monotonic release number. A client rejects a manifest with a lower serial than
 *   one it already accepted, so a host can't replay an old manifest to hide a revocation.
 * @property expiresAtEpochMs after this, the manifest no longer counts as a successful refresh.
 * @property signature reserved for the Ed25519 signature over the manifest body. The spike accepts
 *   unsigned manifests only when the client is configured with `allowUnsigned`.
 */
@Serializable
data class DesignManifest(
  val schema: Int = 1,
  val serial: Long,
  val generatedAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  val minClient: Int = 0,
  val designs: List<DesignEntry>,
  val signature: String? = null,
) {
  companion object {
    const val DEFAULT_TTL_SECONDS: Long = 24 * 60 * 60

    fun blobPath(sha256: String): String = "blobs/$sha256.rc"
  }
}
