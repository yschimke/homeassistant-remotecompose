package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rcplayer.edit.RcApproximation
import ee.schimke.composeai.rcplayer.edit.RcProfiles
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.adaptTo
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits

/**
 * The player a capture is for: the operations its components may use, what its header declares, and
 * (for a launcher) the vocabulary the finished document is rewritten into.
 *
 * It stands where AndroidX's `androidx.compose.remote.creation.profile.Profile` did. That object
 * made AndroidX's writer take a fallback for each operation a player lacks and write the header's
 * feature tags. rc-players' Compose Multiplatform capture has no `Profile`: [supportedOperations]
 * only answers a component that asks, its writer writes `CoreText` whatever the reader is, and its
 * header carries no feature tag. So a capture is finished here ([finish]): the header tags are
 * written into the bytes, and a launcher capture is rewritten by rc-player-edit (`adaptTo`) from
 * the same operation tables.
 */
class CaptureProfile
internal constructor(
  val name: String,
  /** The Remote Compose opcodes a captured document may use. */
  val supportedOperations: Set<Int>,
  /** AndroidX `Header` int tags written into the document's header, by tag. */
  private val header: Map<Int, Int>,
  /** What the finished document may hold, or null to keep it as captured. */
  private val vocabulary: RcTargetProfile? = null,
) {
  /** [captured], as the document this profile's player reads. */
  internal fun finish(captured: ByteArray): ByteArray {
    val adapted =
      vocabulary?.let { target ->
        val adaptation =
          target.decode(captured, RcWireLimits()).adaptTo(target, setOf(RcApproximation.TEXT))
        check(adaptation.isPlayable) {
          "The capture holds what ${target.name} can't read: " +
            adaptation.violations.joinToString("; ") { it.message }
        }
        RcDocumentCodec.encode(adaptation.document)
      } ?: captured
    return adapted.withHeaderTags(header)
  }

  override fun toString(): String = name
}

// AndroidX `Header` tags.
private const val DOC_PROFILES = 14
private const val FEATURE_PAINT_MEASURE = 15
private const val FEATURE_DISALLOW_INTERCEPT_TOUCH = 29

/** The AndroidX embedded player (AndroidX `RcPlatformProfiles.ANDROIDX`). */
val androidX: CaptureProfile =
  CaptureProfile(
    name = "androidX",
    supportedOperations = RcTargetProfiles.ANDROIDX.supportedOpcodes,
    header = mapOf(DOC_PROFILES to RcProfiles.ANDROIDX),
  )

/**
 * The AndroidX player with its experimental operations, notably `FlowLayout` (240), which
 * `RemoteFlowRow` and the glance card's rows need.
 */
val androidXExperimental: CaptureProfile =
  CaptureProfile(
    name = "androidXExperimental",
    supportedOperations = RcTargetProfiles.ANDROIDX_EXPERIMENTAL.supportedOpcodes,
    header = mapOf(DOC_PROFILES to (RcProfiles.ANDROIDX or RcProfiles.EXPERIMENTAL)),
  )

/**
 * [androidXExperimental], for a document a host lays out by its own content size.
 *
 * `FEATURE_PAINT_MEASURE = 0`: the AndroidX View player's default is `1` ("measure in paint"),
 * which makes `RemoteComposeView.onMeasure` report the document's declared size whatever its root
 * measures. Off, the player runs a real measure pass against its parent's bounds and reports the
 * root's own size, which is what a wrap-content card slot needs.
 *
 * `FEATURE_DISALLOW_INTERCEPT_TOUCH = 0`: a card with a drag (a slider) must not take every gesture
 * that starts on it. By default the View player claims the touch from its first frame whenever a
 * document has a touch expression, so a dashboard or the watch's list couldn't scroll past it. Off,
 * the scrolling parent takes vertical drags and the slider horizontal ones. (A launcher widget
 * keeps it on: there a sideways drag would page the home screen.)
 */
val androidXExperimentalWrap: CaptureProfile =
  CaptureProfile(
    name = "androidXExperimentalWrap",
    supportedOperations = RcTargetProfiles.ANDROIDX_EXPERIMENTAL.supportedOpcodes,
    header =
      mapOf(
        DOC_PROFILES to (RcProfiles.ANDROIDX or RcProfiles.EXPERIMENTAL),
        FEATURE_PAINT_MEASURE to 0,
        FEATURE_DISALLOW_INTERCEPT_TOUCH to 0,
      ),
  )

/**
 * AndroidX admits these to its experimental level 7 widget set, but Android 16's platform decoder
 * has none of them: a text is written as the `TextLayout` it reads, and rows take their fallback
 * for `FlowLayout` (`LocalSupportsFlowLayout`).
 */
private val NOT_ON_A_LAUNCHER = setOf(RcOpcodes.CORE_TEXT, RcOpcodes.TEXT_STYLE, RcOpcodes.LAYOUT_FLOW)

private fun launcherProfile(name: String, supportsColorTheme: Boolean): CaptureProfile {
  val experimental = RcTargetProfile.of(7, RcProfiles.WIDGETS or RcProfiles.EXPERIMENTAL)
  val absent =
    NOT_ON_A_LAUNCHER + if (supportsColorTheme) emptySet() else setOf(RcOpcodes.COLOR_THEME)
  val vocabulary =
    experimental.without(*absent.toIntArray(), name = "a launcher widget ($name)")
  return CaptureProfile(
    name = name,
    supportedOperations = vocabulary.supportedOpcodes,
    // No measuring while painting: a launcher re-uses one player view across resizes, and with it
    // on that view paints blank once it is re-measured at a new size.
    header =
      mapOf(DOC_PROFILES to (RcProfiles.WIDGETS or RcProfiles.EXPERIMENTAL), FEATURE_PAINT_MEASURE to 0),
    vocabulary = vocabulary,
  )
}

/**
 * The launcher's platform player (`RemoteViews.DrawInstructions`) on Android 16: the level 7 widget
 * operations with the experimental ones it decodes, and no `ColorTheme` (196).
 */
val widgetsProfile: CaptureProfile = launcherProfile("widgetsProfile", supportsColorTheme = false)

/** An API 37+ launcher: [widgetsProfile] with host-resolved `ColorTheme` values. */
val systemThemedWidgetsProfile: CaptureProfile =
  launcherProfile("systemThemedWidgetsProfile", supportsColorTheme = true)

/**
 * A Wear OS widget's system renderer (AndroidX `RcPlatformProfiles.WEAR_WIDGETS`, what Glance Wear
 * captures with): its components see the Wear widget operations. The document is made a widget's
 * afterwards, for the watch's display (the Wear app's `WearWidgetTemplate.instantiate`), so nothing
 * is rewritten or declared here.
 */
val wearWidgets: CaptureProfile =
  CaptureProfile(
    name = "wearWidgets",
    supportedOperations = RcTargetProfiles.WEAR_WIDGETS.supportedOpcodes,
    header = emptyMap(),
  )

/**
 * [content] captured for [profile]'s player, with rc-players' Compose Multiplatform creation API.
 *
 * @param remoteDensity the density the document's dp and sp read; by default [creationDisplayInfo]'s,
 *   baked in.
 * @param encodePng how this platform turns a bitmap the content draws into PNG bytes; absent, such
 *   bitmaps are dropped.
 */
suspend fun captureRemoteDocument(
  creationDisplayInfo: RemoteCreationDisplayInfo,
  profile: CaptureProfile,
  remoteDensity: RemoteDensity? = null,
  encodePng: ((ImageBitmap) -> ByteArray?)? = null,
  content: @Composable @RemoteComposable () -> Unit,
): ByteArray =
  profile.finish(
    captureCommonRemoteDocument(
      creationDisplayInfo = creationDisplayInfo,
      remoteDensity =
        remoteDensity
          ?: RemoteDensity(creationDisplayInfo.density.rf, creationDisplayInfo.fontScale.rf),
      supportedOperations = profile.supportedOperations,
      encodePng = encodePng,
      content = content,
    ),
  )

/**
 * This document with each of [tags] (AndroidX `Header` int tags, by tag) in its header: an int tag
 * it already has takes the new value, and the rest are added.
 */
internal fun ByteArray.withHeaderTags(tags: Map<Int, Int>): ByteArray {
  if (tags.isEmpty()) return this
  require(size >= HEADER_FIXED && this[0].toInt() == RcOpcodes.HEADER) {
    "Expected a Remote Compose document that starts with its header"
  }
  require(int(1) and -0x10000 == HEADER_MAGIC) { "Expected a header with tagged properties" }
  val out = copyOf()
  val missing = tags.toMutableMap()
  val count = int(COUNT_OFFSET)
  var offset = HEADER_FIXED
  repeat(count) {
    val tag = short(offset)
    val length = short(offset + 2)
    // The low bits are the tag, the high bits its type: 0 is an int.
    if (tag ushr 10 == 0) missing.remove(tag and 0x3f)?.let { out.putInt(offset + 4, it) }
    offset += 4 + length
  }
  if (missing.isEmpty()) return out
  val added = ByteArray(missing.size * 8)
  missing.entries.forEachIndexed { index, (tag, value) ->
    added.putShort(index * 8, tag)
    added.putShort(index * 8 + 2, 4)
    added.putInt(index * 8 + 4, value)
  }
  out.putInt(COUNT_OFFSET, count + missing.size)
  return out.copyOfRange(0, offset) + added + out.copyOfRange(offset, out.size)
}

private const val HEADER_MAGIC = 0x048c0000
// Opcode, major, minor and patch version, then the property count.
private const val COUNT_OFFSET = 13
private const val HEADER_FIXED = 17

private fun ByteArray.short(offset: Int): Int =
  (this[offset].toInt() and 0xff shl 8) or (this[offset + 1].toInt() and 0xff)

private fun ByteArray.int(offset: Int): Int = (short(offset) shl 16) or short(offset + 2)

private fun ByteArray.putShort(offset: Int, value: Int) {
  this[offset] = (value ushr 8).toByte()
  this[offset + 1] = value.toByte()
}

private fun ByteArray.putInt(offset: Int, value: Int) {
  putShort(offset, value ushr 16)
  putShort(offset + 2, value)
}
