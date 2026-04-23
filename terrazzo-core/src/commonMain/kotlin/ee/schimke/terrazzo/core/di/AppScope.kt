package ee.schimke.terrazzo.core.di

import dev.zacsweers.metro.Scope

/**
 * Process-wide scope. Graph bindings marked `@SingleIn(AppScope::class)`
 * get a single instance per graph (i.e. per `Application`).
 */
@Scope
annotation class AppScope
