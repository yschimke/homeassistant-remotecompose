package ee.schimke.ha.rc

/** One thread: nothing to exclude. */
internal actual class PlatformLock actual constructor() {
  actual fun <T> withLock(block: () -> T): T = block()
}

internal actual fun <T> runCaptureBlocking(block: suspend () -> T): T =
  throw UnsupportedOperationException("wasmJs cannot block: capture with the suspending API")
