package ee.schimke.ha.designs

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.ha.cards.CardDesignDocument
import ee.schimke.ha.cards.LocalCardDesignInput
import ee.schimke.ha.client.design.BuiltDesign
import ee.schimke.ha.design.DesignKey
import ee.schimke.ha.rc.CardDesignTemplates
import ee.schimke.ha.rc.DesignTemplateInput
import java.io.File

/**
 * The card designs checked in under `ui-builder/designs/`, one `<cardType>.uid` per migrated card
 * type (server-owned-card-designs.md, "Migrating a card type"). A migrated type's library entries
 * are captured from its design, so an edit to the `.uid` is what the library publishes; every other
 * type still comes from its converter.
 */
object CheckedInCardDesigns {
  /** The checked-in designs, relative to the module (Gradle's test working directory). */
  val DIRECTORY = File("ui-builder/designs")

  private val designs: Map<String, CardDesignDocument> by lazy {
    DIRECTORY.listFiles { file -> file.extension == "uid" }
      .orEmpty()
      .associate { it.nameWithoutExtension to CardDesignDocument.parse(it.readText()) }
  }

  /** The design [cardType] is drawn from, or null while it is still drawn by its converter. */
  fun designFor(cardType: String): CardDesignDocument? = designs[cardType]

  /** The library entry for [key]: from the card type's design when it has one. */
  fun capture(context: Context, key: DesignKey): BuiltDesign {
    val design = designFor(key.cardType) ?: return CardDesignTemplates.capture(context, key)
    return captureDesign(context, key, design)
  }

  /** [design] captured for [key], through the same harness as the converter's template. */
  fun captureDesign(context: Context, key: DesignKey, design: CardDesignDocument): BuiltDesign =
    CardDesignTemplates.captureWith(context, key) { card, snapshot, modifier ->
      design.RenderCard(card, snapshot, modifier)
    }
}
