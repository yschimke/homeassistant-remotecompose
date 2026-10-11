@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteFloat
import androidx.compose.remote.creation.compose.state.RemoteInt
import androidx.compose.remote.creation.compose.state.RemoteString
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.ri
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Bindings for **card design templates** (`docs/architecture/card-design-server.md`): text and
 * numbers a live capture bakes from config, which a template must bind instead so the client can
 * fill them in.
 *
 * Non-null only while a template is being captured. It holds one variable per binding name, so a
 * name used in several places (the visible text, an icon's content description, a chip) is one
 * named variable, not several. Live captures leave this null and get literals, exactly as before.
 */
val LocalTemplateBindings = staticCompositionLocalOf<TemplateBindings?> { null }

/** The named variables of one template capture: see [LocalTemplateBindings]. */
class TemplateBindings {
  internal val strings = mutableMapOf<String, RemoteString>()
  internal val floats = mutableMapOf<String, RemoteFloat>()
  internal val ints = mutableMapOf<String, RemoteInt>()
}

/**
 * The display name of [entityId]: the `<entityId>.name` binding inside a template capture,
 * otherwise [name] as a literal.
 */
@Composable
fun entityName(entityId: String?, name: String): RemoteString {
  val bindings = LocalTemplateBindings.current ?: return name.rs
  if (entityId == null) return name.rs
  return bindings.strings.getOrPut("$entityId.name") { LiveValues.named(entityId, "name", name) }
}

/**
 * A card's title: the `card.title` binding inside a template capture, otherwise [title] as a
 * literal. The title's length also picks a font size ([adaptiveTitleSizeSp]), so cards whose layout
 * depends on it declare that size bucket as a generation property.
 */
@Composable fun cardTitle(title: String): RemoteString = cardText("title", title)

/**
 * The prefix of card-level bindings for the card being captured: `card` for a top-level card, and
 * `card.c<k>` for the k-th child of a container, so two children's `title`s are two bindings.
 */
val LocalCardBindingPrefix = staticCompositionLocalOf { CARD_BINDING_PREFIX }

/** Captures [content] as the [index]-th child of the current card: see [LocalCardBindingPrefix]. */
@Composable
fun ProvideChildCardBindings(index: Int, content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalCardBindingPrefix provides childCardBindingPrefix(LocalCardBindingPrefix.current, index),
    content = content,
  )
}

/** The card-level binding prefix of child [index] under [parent]. */
fun childCardBindingPrefix(parent: String, index: Int): String = "$parent.c$index"

/** Card-level text `card.<field>`: a binding inside a template capture, else [value] literally. */
@Composable
fun cardText(field: String, value: String): RemoteString {
  val bindings = LocalTemplateBindings.current ?: return value.rs
  val prefix = LocalCardBindingPrefix.current
  return bindings.strings.getOrPut("$prefix.$field") { LiveValues.named(prefix, field, value) }
}

/**
 * A card-level number from config, `card.<field>` (a gauge's `min`, a severity threshold): a named
 * float inside a template capture, otherwise the constant [value]. Either way the document computes
 * with it at playback, so nothing that depends on it is decided at capture time.
 */
@Composable
fun cardFloat(field: String, value: Float): RemoteFloat {
  val bindings = LocalTemplateBindings.current ?: return value.rf
  val prefix = LocalCardBindingPrefix.current
  return bindings.floats.getOrPut("$prefix.$field") { LiveValues.namedFloat(prefix, field, value) }
}

/**
 * Text derived from [entityId]'s data that a live capture bakes (a unit, an aggregate):
 * `<entityId>.<field>` inside a template capture, otherwise [value] as a literal.
 */
@Composable
fun entityText(entityId: String?, field: String, value: String): RemoteString {
  val bindings = LocalTemplateBindings.current ?: return value.rs
  if (entityId == null) return value.rs
  return bindings.strings.getOrPut("$entityId.$field") { LiveValues.named(entityId, field, value) }
}

/**
 * A number derived from [entityId]'s data that a live capture bakes (a sparkline's y-range):
 * `<entityId>.<field>` as a named float inside a template capture, otherwise the constant [value].
 */
@Composable
fun entityFloat(entityId: String?, field: String, value: Float): RemoteFloat {
  val bindings = LocalTemplateBindings.current ?: return value.rf
  if (entityId == null) return value.rf
  return bindings.floats.getOrPut("$entityId.$field") {
    LiveValues.namedFloat(entityId, field, value)
  }
}

/**
 * An index derived from [entityId]'s state that a live capture bakes (which accent a state picks):
 * `<entityId>.<field>` as a named int inside a template capture, otherwise the constant [value].
 */
@Composable
fun entityInt(entityId: String?, field: String, value: Int): RemoteInt {
  val bindings = LocalTemplateBindings.current ?: return value.ri
  if (entityId == null) return value.ri
  return bindings.ints.getOrPut("$entityId.$field") { LiveValues.namedInt(entityId, field, value) }
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
