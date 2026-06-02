@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteFlowRow
import androidx.compose.remote.creation.compose.layout.RemoteImage
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.remote.creation.compose.text.RemoteTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * HA `markdown` card — renders parsed markdown blocks inside the card
 * chrome. Headings, bullets and paragraphs each pick a block-level
 * style. Plain blocks emit a single [RemoteText]; blocks that carry
 * links or images ([MarkdownBlock.hasInlineMarkup]) lay their inline
 * runs out as a flow row so link labels (accent colour, clickable) and
 * badge images sit inline and wrap — falling back to a non-wrapping
 * row on capture profiles without the experimental FlowLayout op.
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdown(data: HaMarkdownData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    RemoteBox(
        modifier = modifier
            .fillMaxWidth()
            .then(cardChrome(theme.cardBackground, theme.divider))
            .padding(horizontal = 12.rdp, vertical = 10.rdp),
    ) {
        RemoteColumn {
            if (data.title != null) {
                RemoteText(
                    text = data.title.rs,
                    color = theme.primaryText.rc,
                    fontSize = adaptiveTitleSizeSp(data.title).rsp,
                    fontWeight = FontWeight.Medium,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteBox(modifier = RemoteModifier.padding(top = 4.rdp))
            }
            data.blocks.forEach { block ->
                when (block.kind) {
                    MarkdownBlock.Kind.Heading -> {
                        val size = when (block.level) {
                            1 -> 16
                            2 -> 15
                            3 -> 14
                            else -> 13
                        }
                        MarkdownLine(
                            block = block,
                            theme = theme,
                            fontSize = size,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    MarkdownBlock.Kind.Bullet ->
                        MarkdownLine(
                            block = block,
                            theme = theme,
                            fontSize = 13,
                            fontWeight = FontWeight.Normal,
                            bullet = true,
                        )
                    MarkdownBlock.Kind.Divider -> {
                        RemoteBox(
                            modifier = RemoteModifier
                                .padding(vertical = 4.rdp)
                                .fillMaxWidth()
                                .height(1.rdp)
                                .background(theme.divider.rc),
                        )
                    }
                    MarkdownBlock.Kind.Paragraph ->
                        MarkdownLine(
                            block = block,
                            theme = theme,
                            fontSize = 13,
                            fontWeight = FontWeight.Normal,
                        )
                }
            }
        }
    }
}

/**
 * Identity tier for the `markdown` family — the title plus the first
 * line of body. The smallest cell that still says "this is the X note
 * and it starts with…"; drops the rest of the body (P5) but keeps the
 * P1 identity (title + first line). Used by the Fixed-mode converter at
 * narrow launcher / Wear cells; see
 * docs/architecture/adaptive-card-layouts.md §"Bulk / time-series".
 */
@Composable
@RemoteComposable
fun RemoteHaMarkdownIdentity(data: HaMarkdownData, modifier: RemoteModifier = RemoteModifier) {
    val theme = haTheme()
    val firstLine = data.blocks.firstOrNull { it.kind != MarkdownBlock.Kind.Divider }
    RemoteBox(
        modifier = modifier
            .fillMaxSize()
            .then(cardChrome(theme.cardBackground, theme.divider))
            .padding(horizontal = 12.rdp, vertical = 10.rdp),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteColumn(
            modifier = RemoteModifier.fillMaxWidth(),
            verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
        ) {
            if (data.title != null) {
                RemoteText(
                    text = data.title.rs,
                    color = theme.primaryText.rc,
                    fontSize = 16.rsp,
                    fontWeight = FontWeight.SemiBold,
                    style = RemoteTextStyle.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (firstLine != null) {
                RemoteText(
                    text = firstLine.boundText ?: firstLine.text.rs,
                    color = theme.secondaryText.rc,
                    fontSize = 13.rsp,
                    style = RemoteTextStyle.Default,
                    maxLines = if (data.title != null) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One heading / bullet / paragraph line. Plain blocks render as a single
 * [RemoteText] (preserving the live `boundText` binding); blocks with
 * links or images render their inline runs in a flow row.
 */
@Composable
@RemoteComposable
private fun MarkdownLine(
    block: MarkdownBlock,
    theme: HaTheme,
    fontSize: Int,
    fontWeight: FontWeight,
    bullet: Boolean = false,
) {
    val primary = theme.primaryText.rc
    if (!block.hasInlineMarkup) {
        val text = block.boundText ?: block.text.rs
        RemoteText(
            text = if (bullet) "• ".rs + text else text,
            color = primary,
            fontSize = fontSize.rsp,
            fontWeight = fontWeight,
            style = RemoteTextStyle.Default,
        )
        return
    }

    val link = theme.linkText.rc
    val content: @Composable () -> Unit = {
        if (bullet) {
            RemoteText(
                text = "• ".rs,
                color = primary,
                fontSize = fontSize.rsp,
                fontWeight = fontWeight,
                style = RemoteTextStyle.Default,
            )
        }
        block.inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Text ->
                    if (inline.boundText != null) {
                        // A live `{{ states('x') }}` run — render the bound
                        // string as one node so it keeps updating (can't be
                        // word-split like a constant run).
                        RemoteText(
                            text = inline.boundText,
                            color = primary,
                            fontSize = fontSize.rsp,
                            fontWeight = fontWeight,
                            style = RemoteTextStyle.Default,
                        )
                    } else {
                        // Split into words so the flow row can wrap between
                        // them; the row's horizontal spacing stands in for
                        // the collapsed inter-word whitespace.
                        inline.text.split(WORD_SPLIT)
                            .filter { it.isNotEmpty() }
                            .forEach { word ->
                                RemoteText(
                                    text = word.rs,
                                    color = primary,
                                    fontSize = fontSize.rsp,
                                    fontWeight = fontWeight,
                                    style = RemoteTextStyle.Default,
                                )
                            }
                    }
                is MarkdownInline.Link -> {
                    val click = HaAction.Url(inline.url).toRemoteAction()
                    RemoteText(
                        text = inline.boundText ?: inline.text.rs,
                        color = link,
                        fontSize = fontSize.rsp,
                        fontWeight = fontWeight,
                        style = RemoteTextStyle.Default,
                        modifier = click?.let { RemoteModifier.clickable(it) } ?: RemoteModifier,
                    )
                }
                is MarkdownInline.Image -> MarkdownBadge(inline)
            }
        }
    }

    // Wrap inline runs so a long badge row / link list flows onto more
    // lines. Surfaces whose capture writer rejects FlowLayout (Glance
    // Wear) degrade to a non-wrapping row.
    if (LocalSupportsFlowLayout.current) {
        RemoteFlowRow(
            modifier = RemoteModifier.fillMaxWidth(),
            horizontalArrangement = RemoteArrangement.spacedBy(4.rdp),
            verticalArrangement = RemoteArrangement.spacedBy(2.rdp),
        ) { content() }
    } else {
        RemoteRow(horizontalArrangement = RemoteArrangement.spacedBy(4.rdp)) { content() }
    }
}

/**
 * Inline badge / image. The bytes are fetched by URL at playback via the
 * host's `BitmapLoader`, so an embedding surface using markdown images
 * must wire one (e.g. `CoilBitmapLoader`); offline previews fall back to
 * the host's placeholder bitmap. A `[![…](src)](href)` badge links to
 * [MarkdownInline.Image.href] when present.
 *
 * Uses [rememberLocalNamedRemoteBitmap]'s sized URL form rather than
 * [RemoteHaImageUrl] — the upstream URL bitmap defaults to a 1×1 decode
 * slot, which the player rejects once the fetched badge is larger
 * ("dimensions don't match"). [BADGE_DECODE_MAX_PX] is the decode bound,
 * not the draw size; the modifier fixes the on-screen box.
 */
@Composable
@RemoteComposable
private fun MarkdownBadge(image: MarkdownInline.Image) {
    val click = image.href?.let { HaAction.Url(it).toRemoteAction() }
    val rb = rememberLocalNamedRemoteBitmap(
        name = image.url,
        url = image.url,
        width = BADGE_DECODE_MAX_W_PX,
        height = BADGE_DECODE_MAX_H_PX,
    )
    RemoteImage(
        remoteBitmap = rb,
        contentDescription = image.alt.ifEmpty { "image" }.rs,
        modifier = (click?.let { RemoteModifier.clickable(it) } ?: RemoteModifier)
            .height(BADGE_HEIGHT_DP.rdp)
            .width(BADGE_WIDTH_DP.rdp),
        contentScale = ContentScale.Fit,
    )
}

// Badge SVGs are ~20 px tall and a few times wider; a fixed box keeps the
// flow row tidy and lets several badges share a line. Fit letterboxes any
// odd aspect rather than distorting it.
private const val BADGE_HEIGHT_DP = 18
private const val BADGE_WIDTH_DP = 96

// Decode-bound (max) for URL badge fetches. Badges are wide-and-short, so
// the bound matches that aspect (a square bound would reject a normal
// badge whose width exceeds it). The fetched bitmap must fit within both
// dimensions; the on-screen size is set by the modifier above. Kept small
// so a card full of badges stays well under the player's per-document
// bitmap-memory budget (384×128×4 ≈ 196 KB each).
private const val BADGE_DECODE_MAX_W_PX = 384
private const val BADGE_DECODE_MAX_H_PX = 128

private val WORD_SPLIT = Regex("\\s+")
