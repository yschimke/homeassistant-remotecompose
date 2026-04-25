package ee.schimke.ha.model

/**
 * Encoded RemoteCompose document bytes, sized for a specific surface. Storage format on disk (Room
 * blob), wire format on the network (`application/x-remote-compose`), and the value type generators
 * return.
 *
 * Kept in `ha-model` so both the JVM add-on and KMP client targets can see it; `rc-converter`
 * (Android) keeps its own `CardDocument` for the `decode() → CoreDocument` convenience but is
 * structurally a superset of this.
 */
class CardBytes(val bytes: ByteArray, val widthPx: Int, val heightPx: Int) {
  override fun equals(other: Any?): Boolean =
    other is CardBytes &&
      bytes.contentEquals(other.bytes) &&
      widthPx == other.widthPx &&
      heightPx == other.heightPx

  override fun hashCode(): Int = (bytes.contentHashCode() * 31 + widthPx) * 31 + heightPx

  override fun toString(): String =
    "CardBytes(bytes=${bytes.size}B, width=$widthPx, height=$heightPx)"
}
