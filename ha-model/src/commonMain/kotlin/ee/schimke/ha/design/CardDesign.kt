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
 * @property theme how colours are resolved. The spike only produces baked themes
 *   (`baked:<ThemeStyle>:<light|dark>`); `host` is reserved for designs whose colours come from
 *   `theme.*` bindings, `ColorTheme` or `WearM3`.
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
  companion object {
    const val DEFAULT_VARIANT: String = "default"
    const val BINDING_SCHEMA_VERSION: Int = 1

    fun bakedTheme(style: String, dark: Boolean): String =
      "baked:$style:${if (dark) "dark" else "light"}"
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
)

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
