package ee.schimke.ha.rc

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

@PublishedApi internal actual fun traceBegin(name: String) = Trace.beginSection(name)

@PublishedApi internal actual fun traceEnd() = Trace.endSection()

private val asyncTraceCookies = AtomicInteger()

internal actual fun asyncTraceBegin(name: String): Int =
  asyncTraceCookies.incrementAndGet().also { Trace.beginAsyncSection(name, it) }

internal actual fun asyncTraceEnd(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
