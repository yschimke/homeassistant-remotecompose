package ee.schimke.ha.rc

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import ee.schimke.composeai.rccreation.common.HTag
import ee.schimke.composeai.rccreation.common.Header
import ee.schimke.composeai.rccreation.common.Profile
import ee.schimke.composeai.rccreation.common.RcPlatformProfiles
import ee.schimke.composeai.rccreation.common.RcProfiles
import ee.schimke.composeai.rccreation.compose.capture.Profile
import ee.schimke.composeai.rccreation.compose.capture.RemoteCreationDisplayInfo
import ee.schimke.composeai.rccreation.compose.capture.RemoteDensity
import ee.schimke.composeai.rccreation.compose.capture.captureCommonRemoteDocument
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.state.rf
import ee.schimke.composeai.rcplayer.edit.RcProfiles as RcTargetFlags
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcwidget.creation.RcCaptureProfiles
import ee.schimke.composeai.rcwidget.creation.toCaptureProfile

/**
 * The player a capture is for: rc-players' capture [Profile] (AndroidX's
 * `androidx.compose.remote.creation.profile.Profile`, for the Compose Multiplatform creation API).
 * Its writer refuses an operation the player does not read, takes the fallback where there is one
 * (text as a `TextLayout` for a launcher) and writes the header the profile asks for, so a capture
 * needs nothing done to it afterwards.
 */
typealias CaptureProfile = Profile

/** The AndroidX embedded player. */
val androidX: CaptureProfile = RcPlatformProfiles.ANDROIDX

/**
 * The AndroidX player with its experimental operations, notably `FlowLayout` (240), which
 * `RemoteFlowRow` and the glance card's rows need.
 */
val androidXExperimental: CaptureProfile = RcCaptureProfiles.androidXExperimental

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
 * keeps it on: there a sideways drag would page the home screen.) This tag is why the profile is
 * the app's own and not `RcCaptureProfiles.androidXExperimentalWrap`, which has only the first.
 */
val androidXExperimentalWrap: CaptureProfile =
  Profile(
    profileFlags = RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
    headerTags =
      listOf(
        HTag(Header.FEATURE_PAINT_MEASURE, 0),
        HTag(Header.FEATURE_DISALLOW_INTERCEPT_TOUCH, 0),
      ),
  )

/**
 * AndroidX admits these to its experimental level 7 widget set, but Android 16's platform decoder
 * has none of them: a text is written as the `TextLayout` it reads, and rows take their fallback
 * for `FlowLayout` (`LocalSupportsFlowLayout`).
 */
private val NOT_ON_A_LAUNCHER =
  setOf(RcOpcodes.CORE_TEXT, RcOpcodes.TEXT_STYLE, RcOpcodes.LAYOUT_FLOW)

/**
 * A launcher's profile is the app's own, not `RcCaptureProfiles.launcherWidgets`: that one is cut
 * down to document API level 6's operations, and its writer refuses one the cards use
 * (`TextSubtext`, which a relative time's month is cut with). This is the set the AndroidX-API
 * profile had: level 7's widget and experimental operations, less [NOT_ON_A_LAUNCHER].
 */
private fun launcherProfile(supportsColorTheme: Boolean): CaptureProfile =
  RcTargetProfile.of(7, RcTargetFlags.WIDGETS or RcTargetFlags.EXPERIMENTAL)
    .toCaptureProfile(
      without =
        NOT_ON_A_LAUNCHER + if (supportsColorTheme) emptySet() else setOf(RcOpcodes.COLOR_THEME),
      // No measuring while painting: a launcher re-uses one player view across resizes, and with
      // it on that view paints blank once it is re-measured at a new size.
      headerTags = listOf(HTag(Header.FEATURE_PAINT_MEASURE, 0)),
    )

/**
 * The launcher's platform player (`RemoteViews.DrawInstructions`) on Android 16: the level 7 widget
 * operations with the experimental ones it decodes, and no `ColorTheme` (196). The writer takes its
 * fallback for what is left out, so the capture needs no rewriting afterwards.
 */
val widgetsProfile: CaptureProfile = launcherProfile(supportsColorTheme = false)

/** An API 37+ launcher: [widgetsProfile] with host-resolved `ColorTheme` values. */
val systemThemedWidgetsProfile: CaptureProfile = launcherProfile(supportsColorTheme = true)

/**
 * A Wear OS widget's system renderer (AndroidX `RcPlatformProfiles.WEAR_WIDGETS`, what Glance Wear
 * captures with). The document is made a widget's afterwards, for the watch's display (the Wear
 * app's `WearWidgetTemplate.instantiate`).
 */
val wearWidgets: CaptureProfile = RcCaptureProfiles.wearWidgets

/**
 * [content] captured for [profile]'s player, with rc-players' Compose Multiplatform creation API.
 *
 * @param remoteDensity the density the document's dp and sp read; by default
 *   [creationDisplayInfo]'s, baked in.
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
  captureCommonRemoteDocument(
    creationDisplayInfo = creationDisplayInfo,
    remoteDensity =
      remoteDensity
        ?: RemoteDensity(creationDisplayInfo.density.rf, creationDisplayInfo.fontScale.rf),
    profile = profile,
    encodePng = encodePng,
    content = content,
  )
