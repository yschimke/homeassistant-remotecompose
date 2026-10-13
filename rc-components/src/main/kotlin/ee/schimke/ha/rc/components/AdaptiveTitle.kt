@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.RemoteTextUnit

/**
 * A card title's size: [HaType.Title], stepping down the scale for long titles so they fit one line
 * ([HaType.BodyLarge] past 24 characters, [HaType.Body] past 36).
 */
internal fun adaptiveTitleSize(title: String): RemoteTextUnit =
  when {
    title.length > 36 -> HaType.Body
    title.length > 24 -> HaType.BodyLarge
    else -> HaType.Title
  }
