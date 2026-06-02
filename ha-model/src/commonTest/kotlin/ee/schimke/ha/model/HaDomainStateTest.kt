package ee.schimke.ha.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HaDomainStateTest {

  @Test
  fun knownValuesParseToSymbolicStates() {
    assertEquals(OnOffState.On, OnOffState.parse("on"))
    assertEquals(OnOffState.Off, OnOffState.parse("off"))
    assertEquals(CoverState.Opening, CoverState.parse("opening"))
    assertEquals(LockState.Jammed, LockState.parse("jammed"))
    assertEquals(MediaPlayerState.Buffering, MediaPlayerState.parse("buffering"))
    assertEquals(ClimateMode.HeatCool, ClimateMode.parse("heat_cool"))
    assertEquals(AlarmState.ArmedCustomBypass, AlarmState.parse("armed_custom_bypass"))
    assertEquals(VacuumState.Returning, VacuumState.parse("returning"))
    assertEquals(PersonState.NotHome, PersonState.parse("not_home"))
  }

  @Test
  fun everyDomainMapsUnavailable() {
    assertEquals(OnOffState.Unavailable, OnOffState.parse("unavailable"))
    assertEquals(CoverState.Unavailable, CoverState.parse("unavailable"))
    assertEquals(LockState.Unavailable, LockState.parse("unavailable"))
    assertEquals(MediaPlayerState.Unavailable, MediaPlayerState.parse("unavailable"))
    assertEquals(ClimateMode.Unavailable, ClimateMode.parse("unavailable"))
    assertEquals(AlarmState.Unavailable, AlarmState.parse("unavailable"))
    assertEquals(VacuumState.Unavailable, VacuumState.parse("unavailable"))
    assertEquals(PersonState.Unavailable, PersonState.parse("unavailable"))
  }

  @Test
  fun unrecognisedValuesCarryRawThroughUnknown() {
    // The stability promise: a new HA release / custom integration state
    // flows through as Unknown(raw) instead of crashing the converter.
    assertEquals(OnOffState.Unknown("flickering"), OnOffState.parse("flickering"))
    assertEquals(ClimateMode.Unknown("eco_boost"), ClimateMode.parse("eco_boost"))
    assertEquals(AlarmState.Unknown("armed_pets"), AlarmState.parse("armed_pets"))
    assertEquals("eco_boost", (ClimateMode.parse("eco_boost") as ClimateMode.Unknown).raw)
  }

  @Test
  fun parseIsCaseSensitiveByContract() {
    // HA state strings are canonical lower-case; an upper-case value is an
    // unknown, not a silent match.
    assertEquals(OnOffState.Unknown("ON"), OnOffState.parse("ON"))
  }

  @Test
  fun alarmIsArmedOnlyForArmedVariants() {
    listOf(
        AlarmState.ArmedHome,
        AlarmState.ArmedAway,
        AlarmState.ArmedNight,
        AlarmState.ArmedVacation,
        AlarmState.ArmedCustomBypass,
      )
      .forEach { assertTrue(it.isArmed, "$it should be armed") }
    listOf(
        AlarmState.Disarmed,
        AlarmState.Triggered,
        AlarmState.Pending,
        AlarmState.Arming,
        AlarmState.Disarming,
        AlarmState.Unavailable,
        AlarmState.Unknown("x"),
      )
      .forEach { assertFalse(it.isArmed, "$it should not be armed") }
  }

  @Test
  fun alarmIntKeysAreStableDistinctAndDeclarationOrdered() {
    // These keys are a host <-> .rc wire contract: stable, distinct, and in
    // the same order as AlarmStateInt.All.
    val expected =
      listOf(
        AlarmState.Disarmed to 0,
        AlarmState.ArmedHome to 1,
        AlarmState.ArmedAway to 2,
        AlarmState.ArmedNight to 3,
        AlarmState.ArmedVacation to 4,
        AlarmState.ArmedCustomBypass to 5,
        AlarmState.Triggered to 6,
        AlarmState.Pending to 7,
        AlarmState.Arming to 8,
        AlarmState.Disarming to 9,
        AlarmState.Unavailable to 10,
        AlarmState.Unknown("whatever") to 11,
      )
    expected.forEach { (state, key) -> assertEquals(key, state.intKey(), "$state") }

    assertEquals(expected.map { it.second }, AlarmStateInt.All.toList())
    assertEquals(AlarmStateInt.All.size, AlarmStateInt.All.toSet().size, "keys must be distinct")
  }

  @Test
  fun alarmStateIntFromRawComposesParseAndIntKey() {
    assertEquals(AlarmStateInt.ArmedAway, alarmStateIntFromRaw("armed_away"))
    assertEquals(AlarmStateInt.Unknown, alarmStateIntFromRaw("armed_pets"))
  }
}
