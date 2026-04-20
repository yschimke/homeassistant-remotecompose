package ee.schimke.ha.integration

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tiny per-pixel image comparator — no external deps, just `javax.imageio`.
 *
 * Trades precision for clarity: compares two RGB buffers at their
 * intersection (smaller of the two in each dimension), treats any pixel
 * whose max-channel delta exceeds `tolerance` as "changed", and reports
 * overall percent-changed.
 */
object ImageDiff {
    data class Report(
        val aPath: String,
        val bPath: String,
        val width: Int,
        val height: Int,
        val changed: Int,
        val total: Int,
        val maxDelta: Int,
    ) {
        val pctChanged: Double get() = if (total == 0) 0.0 else 100.0 * changed / total
        override fun toString(): String =
            "%dx%d  changed=%.2f%% (%d/%d)  maxDelta=%d  a=%s  b=%s".format(
                width, height, pctChanged, changed, total, maxDelta,
                File(aPath).name, File(bPath).name,
            )
    }

    fun compare(a: File, b: File, tolerance: Int = 8): Report {
        val ia = ImageIO.read(a) ?: error("can't decode $a")
        val ib = ImageIO.read(b) ?: error("can't decode $b")
        val w = min(ia.width, ib.width)
        val h = min(ia.height, ib.height)

        var changed = 0
        var maxDelta = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pa = ia.getRGB(x, y)
                val pb = ib.getRGB(x, y)
                if (pa == pb) continue
                val delta = maxChannelDelta(pa, pb)
                if (delta > maxDelta) maxDelta = delta
                if (delta > tolerance) changed++
            }
        }
        return Report(
            aPath = a.absolutePath,
            bPath = b.absolutePath,
            width = w, height = h,
            changed = changed, total = w * h,
            maxDelta = maxDelta,
        )
    }

    private fun maxChannelDelta(a: Int, b: Int): Int {
        val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
        val br = (b shr 16) and 0xff; val bg = (b shr 8) and 0xff; val bb = b and 0xff
        return max(abs(ar - br), max(abs(ag - bg), abs(ab - bb)))
    }
}
