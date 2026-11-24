package ee.schimke.terrazzo.core.di

/**
 * Process-wide scope. Graph bindings marked `@SingleIn(AppScope::class)` get a single instance per
 * graph (i.e. per `Application`).
 */
typealias AppScope = dev.zacsweers.metro.AppScope
