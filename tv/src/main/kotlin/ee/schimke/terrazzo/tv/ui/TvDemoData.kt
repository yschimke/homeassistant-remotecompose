package ee.schimke.terrazzo.tv.ui

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lightweight, TV-only demo fixture. Mirrors the shape of the phone's
 * `DemoData` (animated sensors / lights / power) but stays inside this
 * module — the TV doesn't pair with a phone, so demo mode here is a
 * local toggle that drives the kiosk preview off these values.
 *
 * Values drift with wall-clock time so the room sees motion instead of
 * a frozen screen.
 */
object TvDemoData {

    data class Tile(
        val label: String,
        val value: String,
    )

    fun tiles(nowMs: Long = System.currentTimeMillis()): List<Tile> {
        val tSec = nowMs / 1000.0
        val livingTemp = 21.0 + 2.0 * sin(tSec / 60.0)
        val humidity = (48.0 + 10.0 * sin(tSec / 20.0)).roundToInt()
        val power = (170.0 + 90.0 * sin(tSec / 7.0)).roundToInt()
        val lightsOn = (nowMs / 8_000L) % 2L == 0L
        val mediaTrack = MEDIA_QUEUE[(nowMs / 6_000L % MEDIA_QUEUE.size).toInt()]
        return listOf(
            Tile("Lights", if (lightsOn) "ON · 70%" else "OFF"),
            Tile("Climate", "%.1f °C".format(livingTemp)),
            Tile("Humidity", "$humidity%"),
            Tile("Power", "$power W"),
            Tile("Media", mediaTrack),
        )
    }

    private val MEDIA_QUEUE = listOf(
        "Spotify · Sketches",
        "Radio · Studio Brussel",
        "Podcast · Lex",
        "Spotify · Late Night",
    )
}
