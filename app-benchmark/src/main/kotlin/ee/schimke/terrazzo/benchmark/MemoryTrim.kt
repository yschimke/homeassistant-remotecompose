package ee.schimke.terrazzo.benchmark

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope

/**
 * Sends [packageName] home and asks it to trim as a cached process
 * (`ComponentCallbacks2.TRIM_MEMORY_BACKGROUND`), then waits for ART's background collection: it
 * runs a few seconds after the process leaves the foreground and gives freed heap pages back.
 *
 * `am send-trim-memory` delivers a UI-hidden or deeper level only to a process that is no longer in
 * the foreground, hence the pause after going home.
 */
internal fun MacrobenchmarkScope.trimInBackground(packageName: String) {
  pressHome()
  Thread.sleep(LEAVE_FOREGROUND_MS)
  val result = device.executeShellCommand("am send-trim-memory $packageName BACKGROUND").trim()
  if (result.isNotEmpty()) Log.w("MemoryTrim", "send-trim-memory: $result")
  Thread.sleep(BACKGROUND_GC_MS)
}

private const val LEAVE_FOREGROUND_MS = 1_000L

/** ART's collector transition waits about five seconds after the process goes to the background. */
private const val BACKGROUND_GC_MS = 8_000L
