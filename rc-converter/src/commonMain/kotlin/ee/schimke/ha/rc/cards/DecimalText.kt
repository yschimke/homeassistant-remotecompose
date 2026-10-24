package ee.schimke.ha.rc.cards

import ee.schimke.ha.rc.formatFixed

/**
 * [value] as a whole number when it is one, otherwise with [decimals] places. Always a `.`
 * separator, whatever the device locale: card text has to match `formatState`, which is locale-free
 * so every platform shows the same string.
 */
internal fun decimalText(value: Double, decimals: Int): String =
  if (value == value.toLong().toDouble()) value.toLong().toString()
  else formatFixed(value, decimals)
