package ee.schimke.ha.rc.cards

import ee.schimke.ha.rc.CardConverter
import ee.schimke.ha.rc.CardRegistry

/**
 * Built-in converters that ship with the library. Extend via
 * `CardRegistry.register(...)` for custom cards.
 */
fun defaultConverters(): List<CardConverter> = listOf(
    TileCardConverter(),
)

fun defaultRegistry(): CardRegistry = CardRegistry(defaultConverters())
