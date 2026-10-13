@file:Suppress("RestrictedApi")

package ee.schimke.ha.rc.components

import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------------------------
// Design tokens every Home Assistant card part draws with. The rules, and why each one exists, are
// in docs/architecture/card-component-system.md; in short:
//
// 1. **Chrome colours come from the theme** ([RemoteHaTheme], read through `haTheme()`): card
//    background, text, divider. Never a literal, so the four Terrazzo palettes, Material You and
// the
//    launcher's system colours all re-skin a card without a code change.
// 2. **Data colours are semantic and fixed across palettes**: an entity's state accent
//    (`HaStateColor` in :rc-converter), and the meter colours below ([HaStatusColors],
//    [HaTemperatureColors]). A red warning is red in every theme.
// 3. **Text sizes come from [HaType]**, an eleven-step role scale, not per-component `N.rsp`.
// 4. **Spacing comes from [HaSpace]**; corner radii from [HaShape].
// 5. **Parts draw no chrome.** Only a card (or `HaCardSurface`) draws the rounded background, and
//    only through `cardChrome`, which honours `ProvideCardChrome` — so the same part sits in a
//    dashboard card, a Wear widget container or a launcher cell without a card-in-a-card.
// ---------------------------------------------------------------------------------------------

/**
 * The card type scale: one role per job a piece of text does. Weights are a per-role convention
 * (documented on each), not part of the token, so a component can still emphasise a label.
 *
 * Every `RemoteHa*` composable picks from these roles by what the text *is*, not how big it was:
 * card titles are [Title] (the old 15sp and 16sp titles met there), readings are [Value] (the
 * gauge's 22sp and the metric's 24sp met there), and so on. The clock face is the one exception:
 * its numerals are sized to the card, not to a role.
 */
object HaType {
  /** 10sp — axis ticks, range ends, the smallest captions on a widget. */
  val Micro = 10.rsp

  /** 11sp — secondary metadata: timestamps, range lines, a glance cell's state. */
  val Caption = 11.rsp

  /** 12sp — labels and secondary state text; the default `secondaryText` size. */
  val Label = 12.rsp

  /** 13sp — row names and tile primary text (HA's 13px card body). */
  val Body = 13.rsp

  /** 14sp — emphasised body: an entity row's name on a roomy card, list items. Medium weight. */
  val BodyLarge = 14.rsp

  /** 16sp — card titles and section headings. Medium/SemiBold. */
  val Title = 16.rsp

  /** 18sp — a reading in a compact presentation (widget chip, Wear). SemiBold. */
  val ValueSmall = 18.rsp

  /** 22sp — a heading card's title: section headlines above a group of cards. Medium. */
  val Headline = 22.rsp

  /** 24sp — the primary reading of a card (gauge, sensor, thermometer). SemiBold. */
  val Value = 24.rsp

  /** 28sp — a reading promoted on an expanded tier, or a hero value. SemiBold. */
  val ValueLarge = 28.rsp

  /** 32sp — display numerals: a clock face, a thermostat target. */
  val Display = 32.rsp
}

/** Spacing steps, in dp. Pass through `.rdp` at the call site. */
object HaSpace {
  const val Hairline = 1
  const val XXS = 2
  const val XS = 4
  const val S = 6
  const val M = 8
  const val L = 12
  const val XL = 16
}

/** Corner radii, in dp. */
object HaShape {
  /** The outer corner every card draws (`cardChrome`'s default). */
  const val Card = 12
  /** An icon chip, a status chip, a meter track's ends when not fully round. */
  const val Chip = 8
  /** Fully round ends: switches, pills. */
  const val Pill = 999
}

/**
 * Status colours a meter paints its value with, shared by the gauge's severity bands, a battery's
 * low warnings and any other "is this reading OK?" visual. Fixed across palettes (rule 2): they
 * describe the data, not the chrome.
 */
object HaStatusColors {
  /** No band configured / no judgement: HA's default gauge blue. */
  val None = Color(0xFF03A9F4)
  val Normal = Color(0xFF43A047)
  val Warning = Color(0xFFFFA000)
  val Critical = Color(0xFFE53935)
}

/**
 * The temperature ramp a thermometer fills with: cold blue through comfortable green to hot red.
 * Thresholds are in °C ([HaTemperatureColors.stopsCelsius]); a °F reading is converted first.
 */
object HaTemperatureColors {
  val Freezing = Color(0xFF1E88E5)
  val Cool = Color(0xFF26C6DA)
  val Comfortable = Color(0xFF43A047)
  val Warm = Color(0xFFFFA000)
  val Hot = Color(0xFFE53935)

  /** Lower bound (°C) of each colour after [Freezing]: cool ≥ 10, comfortable ≥ 18, … */
  val stopsCelsius: List<Pair<Float, Color>> =
    listOf(10f to Cool, 18f to Comfortable, 25f to Warm, 30f to Hot)
}
