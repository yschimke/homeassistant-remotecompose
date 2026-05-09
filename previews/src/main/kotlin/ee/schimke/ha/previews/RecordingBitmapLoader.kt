@file:Suppress("RestrictedApi")

package ee.schimke.ha.previews

import androidx.compose.remote.player.core.platform.BitmapLoader
import java.io.InputStream

/**
 * Small probe loader for image-refresh experiments.
 *
 * Wraps a [delegate] [BitmapLoader] and records every `loadBitmap(name)`
 * call in [events], preserving call order.
 */
class RecordingBitmapLoader(
    private val delegate: BitmapLoader,
) : BitmapLoader {

    data class Event(
        val sequence: Int,
        val name: String,
        val timestampMs: Long,
    )

    private val _events = mutableListOf<Event>()
    val events: List<Event> get() = _events

    override fun loadBitmap(name: String): InputStream {
        synchronized(_events) {
            _events += Event(sequence = _events.size + 1, name = name, timestampMs = System.currentTimeMillis())
        }
        return delegate.loadBitmap(name)
    }
}
