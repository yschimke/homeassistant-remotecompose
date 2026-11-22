package ee.schimke.ha.cards

/**
 * The device packs' designs (card-designs/ui-builder/packs, docs/design/device-packs.md), bundled
 * at build time and drawn by [CardDesignDocument]: what `:previews` renders, so each pack design
 * gets a preview and its changes show in a pull request's preview diff.
 */
object DevicePacks {
  /** Every pack design's `<pack>/<file>` path (`garage-door/alert.uid`), in order. */
  val paths: List<String>
    get() = BundledDevicePacks.paths

  /** The pack design at [path], ready to draw, or null when there is none. */
  fun design(path: String): CardDesignDocument? =
    BundledDevicePacks.file(path)?.let(CardDesignDocument::parse)
}
