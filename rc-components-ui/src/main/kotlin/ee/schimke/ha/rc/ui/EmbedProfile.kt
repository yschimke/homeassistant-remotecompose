package ee.schimke.ha.rc.ui

import ee.schimke.ha.rc.CaptureProfile
import ee.schimke.ha.rc.androidXExperimentalWrap

/**
 * Embed profile for Tier-2 Compose-UI wrappers — the AndroidX player's experimental operations
 * (FlowRow / FlowLayout on for `HaGlance`) with the wrap-content measure feature, so the player
 * measures against parent constraints instead of forcing the document's authored size onto the
 * Compose layout.
 */
internal val haUiEmbedProfile: CaptureProfile = androidXExperimentalWrap
