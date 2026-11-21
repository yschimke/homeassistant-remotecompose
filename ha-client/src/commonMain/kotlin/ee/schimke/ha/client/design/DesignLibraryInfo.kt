package ee.schimke.ha.client.design

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The card design server's answer to `GET <base>/info` (`/designs/v1/info`): which parts of the
 * design protocol it serves, in one request. The same shape and rules as the integration's
 * `terrazzo/info` ([ee.schimke.ha.client.TerrazzoInfo]): [protocol] changes only when a change
 * breaks clients, [minClient] is the oldest client protocol still served, and a capability that is
 * missing is not served.
 *
 * A server older than this endpoint answers it 404; [DesignRepository.info] answers null then. A
 * static host (`manifest.json` and `blobs/` on a plain web server) answers the same: it serves only
 * the manifest and blobs.
 */
@Serializable
data class DesignLibraryInfo(
  val protocol: Int,
  @SerialName("min_client") val minClient: Int = 1,
  val version: String? = null,
  val capabilities: DesignLibraryCapabilities = DesignLibraryCapabilities(),
) {
  companion object {
    /** The protocol this client speaks, and the one the design server in this repository serves. */
    const val PROTOCOL = 1
    const val MIN_CLIENT = 1

    /**
     * How the server writes the answer: every field, so `min_client` is there to read, but no
     * capability it leaves out.
     */
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}

/**
 * What the design server serves. Each is a revision, or an object that only gains optional fields;
 * null means not served. Capabilities this client doesn't know are ignored.
 */
@Serializable
data class DesignLibraryCapabilities(
  /** `manifest.json` and `blobs/<sha256>.rc`: the library itself. */
  val manifest: Int? = null,
  /** `POST generate`: the design for a key, captured on the server when the library has none. */
  val generate: Int? = null,
  /** `fonts/fonts.json` and `fonts/<file>`: the fonts designs name. */
  val fonts: Int? = null,
  /** `fonts/glyphs`: glyph ranges for a family. */
  @SerialName("glyph_ranges") val glyphRanges: Int? = null,
  /** `POST filters/bitmap-fonts`: a document with its fonts drawn as bitmaps, for widget hosts. */
  @SerialName("bitmap_fonts") val bitmapFonts: Int? = null,
  /** `channels/<channel>/...`: a pull request's preview library, under the same protocol. */
  val channels: DesignChannelsCapability? = null,
  /**
   * A signed-in device token adds its owner's private designs to the manifest (GitHub sign-in on
   * the server).
   */
  @SerialName("private_designs") val privateDesigns: Int? = null,
  /** `watchface` cards, drawn from published Wear OS faces. */
  @SerialName("watch_faces") val watchFaces: Int? = null,
)

/** Preview channels, and whether this server takes published ones (with its publish token). */
@Serializable data class DesignChannelsCapability(val publish: Boolean = false)
