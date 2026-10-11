@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Bindings for **card design templates** (`docs/architecture/card-design-server.md`): text a live
 * capture bakes from config, which a template must bind instead so the client can fill it in.
 *
 * Non-null only while a template is being captured. The map holds one [RemoteString] per binding
 * name, so a name used in several places (the visible text, an icon's content description, a chip)
 * is one named variable, not several. Live captures leave this null and get literals, exactly as
 * before.
 */
val LocalTemplateBindings = staticCompositionLocalOf<MutableMap<String, RemoteString>?> { null }

/**
 * The display name of [entityId]: the `<entityId>.name` binding inside a template capture,
 * otherwise [name] as a literal.
 */
@Composable
fun entityName(entityId: String?, name: String): RemoteString {
  val bindings = LocalTemplateBindings.current ?: return name.rs
  if (entityId == null) return name.rs
  return bindings.getOrPut("$entityId.name") { LiveValues.named(entityId, "name", name) }
}

/**
 * A card's title: the `card.title` binding inside a template capture, otherwise [title] as a
 * literal. The title's length also picks a font size ([adaptiveTitleSizeSp]), so cards whose layout
 * depends on it declare that size bucket as a generation property.
 */
@Composable
fun cardTitle(title: String): RemoteString {
  val bindings = LocalTemplateBindings.current ?: return title.rs
  return bindings.getOrPut(CARD_TITLE_BINDING) {
    LiveValues.named(CARD_BINDING_PREFIX, "title", title)
  }
}

/** Binding-name prefix for card-level (not per-entity) bindings: `card.title`, … */
const val CARD_BINDING_PREFIX: String = "card"

const val CARD_TITLE_BINDING: String = "$CARD_BINDING_PREFIX.title"

/** Which [adaptiveTitleSizeSp] bucket [title] falls in: 0 (≤ 24 chars), 1 (≤ 36), 2 (longer). */
fun titleSizeBucket(title: String): Int =
  when {
    title.length > 36 -> 2
    title.length > 24 -> 1
    else -> 0
  }

/** A placeholder title whose length falls in [bucket], so a template sizes it like the real one. */
fun placeholderTitle(bucket: Int): String =
  when (bucket) {
    2 -> "T".repeat(40)
    1 -> "T".repeat(30)
    else -> "Title"
  }
