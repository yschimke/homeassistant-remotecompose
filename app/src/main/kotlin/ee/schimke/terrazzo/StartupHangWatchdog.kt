package ee.schimke.terrazzo

import android.os.Looper
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Logs every thread's stack when a launch draws no frame in time, in the builds with test hooks
 * (debug, and the baseline profile and benchmark variants).
 *
 * The phone benchmarks sometimes launch an app that comes to the foreground and then draws nothing:
 * no crash, no ANR (nothing is touching it), no log, until the harness kills it. Without a stack
 * there is nothing to find the block from; this logs one, main thread last, as `HarcStartupHang`.
 */
internal object StartupHangWatchdog {
  private const val TAG = "HarcStartupHang"
  private const val TIMEOUT_SECONDS = 10L

  /** Call first thing in `onCreate`, on the main thread. */
  fun arm() {
    val drawn = CountDownLatch(1)
    Choreographer.getInstance().postFrameCallback { drawn.countDown() }
    Thread(
        { if (!drawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) dumpThreads() },
        TAG,
      )
      .apply { isDaemon = true }
      .start()
  }

  private fun dumpThreads() {
    Log.w(TAG, "No frame ${TIMEOUT_SECONDS}s after launch; thread stacks follow")
    val main = Looper.getMainLooper().thread
    val stacks = Thread.getAllStackTraces()
    (stacks.keys.filter { it != main && it.state != Thread.State.TIMED_WAITING } + main).forEach {
      thread ->
      Log.w(TAG, "thread ${thread.name} ${thread.state}")
      stacks[thread]?.forEach { Log.w(TAG, "    at $it") }
    }
  }
}
