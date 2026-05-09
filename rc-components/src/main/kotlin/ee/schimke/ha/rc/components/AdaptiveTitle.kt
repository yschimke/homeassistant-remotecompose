@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

internal fun adaptiveTitleSizeSp(title: String, baseSp: Int = 15): Int =
    when {
        title.length > 36 -> baseSp - 2
        title.length > 24 -> baseSp - 1
        else -> baseSp
    }
