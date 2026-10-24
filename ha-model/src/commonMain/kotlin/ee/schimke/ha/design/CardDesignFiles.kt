package ee.schimke.ha.design

/**
 * The file a card type's checked-in design lives in: `card-designs/ui-builder/designs/<name>.uid`,
 * packaged and served under the same name. A custom card's type has a `:`
 * (`custom:ha-bambulab-ams-card`), which a Windows checkout can't hold, so the file names it with
 * `--` instead (`custom--ha-bambulab-ams-card.uid`); no built-in card type has either.
 */
object CardDesignFiles {
  const val EXTENSION = "uid"

  /** [cardType]'s design file name, `<name>.uid`. */
  fun fileName(cardType: String): String = "${baseName(cardType)}.$EXTENSION"

  /** [cardType]'s design file name without its extension. */
  fun baseName(cardType: String): String = cardType.replace(":", "--")

  /** The card type a design file's [baseName] (its name without `.uid`) is for. */
  fun cardType(baseName: String): String = baseName.replace("--", ":")
}
