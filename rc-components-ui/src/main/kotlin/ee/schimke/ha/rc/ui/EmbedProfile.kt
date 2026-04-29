@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.ui

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RcProfiles
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.RemoteComposeWriterAndroid
import androidx.compose.remote.creation.platform.AndroidxRcPlatformServices
import androidx.compose.remote.creation.profile.Profile

/**
 * Embed profile for Tier-2 Compose-UI wrappers — AndroidX experimental
 * (FlowRow / FlowLayout ops on for `HaGlance`) with the wrap-content
 * measure feature enabled, so the player measures against parent
 * constraints instead of forcing the document's authored size onto the
 * Compose layout.
 *
 * Mirrors `ee.schimke.ha.rc.androidXExperimentalWrap` from `:rc-converter`
 * — duplicated here so the UI module doesn't pull the converter in.
 */
internal val haUiEmbedProfile: Profile =
    Profile(
        CoreDocument.DOCUMENT_API_LEVEL,
        RcProfiles.PROFILE_ANDROIDX or RcProfiles.PROFILE_EXPERIMENTAL,
        AndroidxRcPlatformServices(),
    ) { creationDisplayInfo, profile, _ ->
        RemoteComposeWriterAndroid(
            profile,
            RemoteComposeWriter.hTag(Header.DOC_WIDTH, creationDisplayInfo.width),
            RemoteComposeWriter.hTag(Header.DOC_HEIGHT, creationDisplayInfo.height),
            RemoteComposeWriter.hTag(Header.DOC_CONTENT_DESCRIPTION, ""),
            RemoteComposeWriter.hTag(Header.DOC_PROFILES, profile.operationsProfiles),
            RemoteComposeWriter.hTag(Header.FEATURE_PAINT_MEASURE, 0),
        )
    }
