package ee.schimke.ha.rc

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSRecursiveLock

internal actual class PlatformLock actual constructor() {
  private val lock = NSRecursiveLock()

  actual fun <T> withLock(block: () -> T): T {
    lock.lock()
    try {
      return block()
    } finally {
      lock.unlock()
    }
  }
}

internal actual fun <T> runCaptureBlocking(block: suspend () -> T): T = runBlocking { block() }
