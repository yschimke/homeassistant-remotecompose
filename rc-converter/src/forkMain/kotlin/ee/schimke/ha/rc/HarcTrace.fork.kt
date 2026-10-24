package ee.schimke.ha.rc

@PublishedApi internal actual fun traceBegin(name: String) {}

@PublishedApi internal actual fun traceEnd() {}

internal actual fun asyncTraceBegin(name: String): Int = 0

internal actual fun asyncTraceEnd(name: String, cookie: Int) {}
