package ee.schimke.terrazzo.wear.widget

import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcStateLayout
import ee.schimke.composeai.rcplayer.protocol.RcSystemVariables
import ee.schimke.composeai.rcplayer.protocol.RcVisibilityModifier
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits

/**
 * A phone card design as a [WearWidgetTemplate]: the same document, its card box made into the Wear
 * widget's container, so a card that has no Wear design of its own still plays as one without being
 * captured again.
 *
 * A card design draws its own surface as one box: a rounded clip, the card's background, in some
 * styles a border, and the card's padding. On the watch that box becomes Glance Wear's container
 * ([WearWidgetTemplate]'s, as androidx's `WearWidgetContainer` draws it): its clip takes the named
 * corner radius, its padding the named horizontal and vertical padding, its border goes, and it and
 * the boxes around it fill the widget, so the card's background is the widget's. Filling the
 * widget, the box also centres its content vertically, as the watch's own capture does: a phone
 * card laid out for its own height would otherwise sit at the top of a taller container, over a
 * blank band. The card's body is otherwise untouched. [WearWidgetTemplate.instantiate] then writes
 * in a widget's own values and rewrites what the Wear widget profile can't play, as it does for the
 * watch's own template.
 *
 * Only a card box that holds the whole card is reframed: a design whose first surface sits beside
 * other content (a grid's or a stack's cells, each a card of its own) has no single card box, and
 * gets no template. A conditional overlay after it (a tile's "unavailable" badge) stays as it is.
 */
internal object CardDesignWearTemplate {

  /** [cardDesign] as a container-neutral Wear widget template, or null when it can't be one. */
  fun template(cardDesign: ByteArray): ByteArray? = runCatching {
    reframe(RcDocumentCodec.decode(cardDesign, RcWireLimits()))?.let(RcDocumentCodec::encode)
  }
    .getOrNull()

  private fun reframe(document: RcDocument): RcDocument? {
    val operations = document.operations
    val box = cardBox(operations) ?: return null
    val ids = freshIds(document, count = 6)
    val (horizontal, vertical, corner) = ids.take(3)
    val (horizontalPx, verticalPx, cornerPx) = ids.drop(3)

    val heights =
      box.filling.flatMap { box.runOf(it) }.filter { operations[it] is RcHeightModifier }.toSet()
    val rewritten = ArrayList<RcOperation>(operations.size + 12)
    operations.forEachIndexed { index, operation ->
      when {
        index == box.layout -> rewritten += centredVertically(operation)
        index in box.run && operation is RcRoundedClipRectModifier ->
          rewritten +=
            RcRoundedClipRectModifier(ref(cornerPx), ref(cornerPx), ref(cornerPx), ref(cornerPx))
        index in box.run && operation is RcBorderModifier -> Unit
        index in box.run && operation is RcPaddingModifier ->
          rewritten +=
            RcPaddingModifier(
              ref(horizontalPx),
              ref(verticalPx),
              ref(horizontalPx),
              ref(verticalPx),
            )
        index in heights -> rewritten += FILL_HEIGHT
        else -> rewritten += operation
      }
      // The box's padding is the container's: added after its background when it has none.
      if (index == box.background && box.run.none { operations[it] is RcPaddingModifier }) {
        rewritten +=
          RcPaddingModifier(ref(horizontalPx), ref(verticalPx), ref(horizontalPx), ref(verticalPx))
      }
      // Each box from the root to the card box fills the widget's height, as the container does.
      if (index in box.filling && box.runOf(index).none { operations[it] is RcHeightModifier }) {
        rewritten += FILL_HEIGHT
      }
      // The container's values, declared before anything lays out (in the root's content).
      if (index == box.rootContent) {
        rewritten += named(horizontal, horizontalPx, WearWidgetTemplate.HORIZONTAL_PADDING, 8f)
        rewritten += named(vertical, verticalPx, WearWidgetTemplate.VERTICAL_PADDING, 8f)
        rewritten += named(corner, cornerPx, WearWidgetTemplate.CORNER_RADIUS, 26f)
      }
    }
    return document.copy(operations = rewritten)
  }

  /**
   * Where a card box is: [layout] is its layout operation, [background] is its background modifier,
   * [run] its modifiers, [filling] the layouts from the root's first box to the card box (each a
   * layout operation's index), and [rootContent] the root's content operation, after which the
   * container's values are declared.
   */
  private class CardBox(
    val layout: Int,
    val rootContent: Int,
    val background: Int,
    val run: IntRange,
    val filling: Set<Int>,
    private val runs: Map<Int, IntRange>,
  ) {
    fun runOf(layout: Int): IntRange = runs.getValue(layout)
  }

  private fun cardBox(operations: List<RcOperation>): CardBox? {
    val rootContent =
      operations.indexOfFirst { it is RcLayoutContent }.takeIf { it >= 0 } ?: return null
    // Open containers, by index, and whether a layout has already closed beside the card box.
    val open = ArrayDeque<Int>()
    var closedLayout = false
    operations.forEachIndexed { index, operation ->
      if (operation.opcode == RcOpcodes.CONTAINER_END) {
        val closed = open.removeLastOrNull() ?: return null
        if (operations[closed].isLayout()) closedLayout = true
        return@forEachIndexed
      }
      if (operation.opcode in RcOpcodes.CONTAINER_STARTS) open.addLast(index)
      if (!operation.isLayout()) return@forEachIndexed
      val run = modifierRun(operations, index)
      val clip =
        run.firstOrNull { operations[it] is RcRoundedClipRectModifier } ?: return@forEachIndexed
      val background = clip + 1
      if (background !in run || operations[background] !is RcBackgroundModifier)
        return@forEachIndexed
      // The first surface: it is the card's box only if nothing laid out beside it came first.
      if (closedLayout) return null
      val filling = open.filter { operations[it].isLayout() }.toSet()
      if (!enclosesTheRest(operations, index)) return null
      return CardBox(
        layout = index,
        rootContent = rootContent,
        background = background,
        run = run,
        filling = filling,
        runs = filling.associateWith { modifierRun(operations, it) },
      )
    }
    return null
  }

  /**
   * Whether the card box at [box] holds the whole card: everything laid out after it is a
   * conditional overlay (a layout with a visibility modifier, such as a tile's "unavailable"
   * badge), not more content beside it (a grid's next cell).
   */
  private fun enclosesTheRest(operations: List<RcOperation>, box: Int): Boolean {
    val end = containerEnd(operations, box) ?: return false
    var index = end + 1
    while (index < operations.size) {
      val operation = operations[index]
      if (operation.isLayout()) {
        if (modifierRun(operations, index).none { operations[it] is RcVisibilityModifier }) {
          return false
        }
        index = (containerEnd(operations, index) ?: return false) + 1
        continue
      }
      index++
    }
    return true
  }

  /** The index of the end of the container [start] opens, or null when it doesn't close. */
  private fun containerEnd(operations: List<RcOperation>, start: Int): Int? {
    var depth = 0
    for (index in start until operations.size) {
      val opcode = operations[index].opcode
      when {
        opcode == RcOpcodes.CONTAINER_END -> if (--depth == 0) return index
        opcode in RcOpcodes.CONTAINER_STARTS -> depth++
      }
    }
    return null
  }

  /** The modifiers of the layout at [layout]: everything up to its content. */
  private fun modifierRun(operations: List<RcOperation>, layout: Int): IntRange {
    val content =
      (layout + 1 until operations.size).firstOrNull { operations[it] is RcLayoutContent }
    return (layout + 1) until (content ?: layout + 1)
  }

  /**
   * [layout] centring its content on the vertical axis. A box or row aligns its children to the
   * middle; a column arranged from the top arranges them about the middle. Any other arrangement (a
   * column spaced evenly, a bottom-aligned row) is the design's own and stays; so does every other
   * layout.
   */
  private fun centredVertically(layout: RcOperation): RcOperation =
    when (layout) {
      is RcBoxLayout ->
        if (layout.verticalPositioning == TOP) layout.copy(verticalPositioning = CENTER) else layout
      is RcRowLayout ->
        if (layout.verticalPositioning == TOP) layout.copy(verticalPositioning = CENTER) else layout
      is RcColumnLayout ->
        if (layout.verticalPositioning == TOP) layout.copy(verticalPositioning = CENTER) else layout
      else -> layout
    }

  private fun RcOperation.isLayout(): Boolean =
    this is RcBoxLayout ||
      this is RcRowLayout ||
      this is RcColumnLayout ||
      this is RcCollapsibleRowLayout ||
      this is RcCollapsibleColumnLayout ||
      this is RcFitBoxLayout ||
      this is RcStateLayout

  /** A named dp value [name] at [id], defaulting to [defaultDp], and its pixels at [pxId]. */
  private fun named(id: Int, pxId: Int, name: String, defaultDp: Float): List<RcOperation> =
    listOf(
      RcNamedVariable(id, RcNamedVariable.FLOAT_TYPE, "$USER_PREFIX$name"),
      RcFloatConstant(id, RcFloatWord.literal(defaultDp)),
      RcFloatExpression(
        pxId,
        listOf(ref(id), ref(RcSystemVariables.DENSITY), MUL),
        animation = null,
      ),
    )

  /**
   * [count] ids [document] doesn't use. Every 4-byte window of its encoding is read as one, which
   * over-counts (any value, not only ids) but never misses one.
   */
  private fun freshIds(document: RcDocument, count: Int): List<Int> {
    val bytes = RcDocumentCodec.encode(document)
    val used = HashSet<Int>()
    for (i in 0..bytes.size - 4) {
      val word =
        (bytes[i].toInt() and 0xff shl 24) or
          (bytes[i + 1].toInt() and 0xff shl 16) or
          (bytes[i + 2].toInt() and 0xff shl 8) or
          (bytes[i + 3].toInt() and 0xff)
      used += word
      used += word and ID_MASK
    }
    return generateSequence(FIRST_ID) { it + 1 }.filter { it !in used }.take(count).toList()
  }

  /** A float word reading id [id]. */
  private fun ref(id: Int) = RcFloatWord(NAN_BOX or id)

  /** RemoteCompose's layout positions (`LayoutManager`): the middle and the top edge. */
  private const val CENTER = 2
  private const val TOP = 4

  private const val USER_PREFIX = "USER:"
  private const val FIRST_ID = 42
  private const val ID_MASK = 0x003fffff
  private const val NAN_BOX = 0xff800000.toInt()

  /** The float expression multiply operator, NaN-boxed as the player reads it. */
  private val MUL = RcFloatWord(NAN_BOX or 0x310003)

  private val FILL_HEIGHT = RcHeightModifier(RcDimensionType.FILL, RcFloatWord.literal(1f))
}
