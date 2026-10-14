package ee.schimke.terrazzo.feedback

import ee.schimke.ha.rc.components.HaAction
import ee.schimke.ha.rc.components.HaUberTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTest {
  @Test
  fun aToggleCuesTheWayItWillFlip() {
    val states = mapOf("light.on" to "on", "light.off" to "off")
    assertEquals(FeedbackCue.ToggleOff, feedbackCueFor(HaAction.Toggle("light.on"), states::get))
    assertEquals(FeedbackCue.ToggleOn, feedbackCueFor(HaAction.Toggle("light.off"), states::get))
    assertEquals(FeedbackCue.Press, feedbackCueFor(HaAction.Toggle("light.unknown"), states::get))
  }

  @Test
  fun serviceCallsCueByDirection() {
    fun call(service: String) = HaAction.CallService("light", service, "light.x")
    assertEquals(FeedbackCue.ToggleOn, feedbackCueFor(call("turn_on")))
    assertEquals(FeedbackCue.ToggleOff, feedbackCueFor(call("close_cover")))
    assertEquals(FeedbackCue.Press, feedbackCueFor(call("activate")))
    assertEquals(FeedbackCue.ToggleOff, feedbackCueFor(call("toggle")) { "on" })
  }

  @Test
  fun onlyActionsThatChangeSomethingAreFelt() {
    assertNull(feedbackCueFor(HaAction.Url("https://example.com")))
    assertNull(feedbackCueFor(HaAction.MoreInfo("light.x")))
    assertNull(feedbackCueFor(HaAction.Navigate("/lovelace/1")))
    assertNull(feedbackCueFor(HaAction.None))
    assertEquals(FeedbackCue.Key, feedbackCueFor(HaAction.AlarmKey("alarm.x", "1")))
    assertEquals(FeedbackCue.Confirm, feedbackCueFor(HaAction.AlarmIntent("alarm.x", "arm_away")))
  }

  @Test
  fun everyCueIsShortQuietAndDeterministic() {
    for (voice in SoundVoice.entries) {
      for (cue in FeedbackCue.entries) {
        val pcm = synthesizeCue(voice, cue)
        assertTrue(pcm.isNotEmpty(), "$voice $cue is silent")
        assertTrue(pcm.size <= FeedbackSampleRate / 4, "$voice $cue is longer than 250 ms")
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue(peak <= FeedbackPeak * Short.MAX_VALUE + 1, "$voice $cue peaks at $peak")
        assertContentEquals(pcm, synthesizeCue(voice, cue))
      }
    }
  }

  @Test
  fun voicesSoundDifferent() {
    val soft = synthesizeCue(SoundVoice.Soft, FeedbackCue.ToggleOn)
    assertFalse(soft.contentEquals(synthesizeCue(SoundVoice.Mechanical, FeedbackCue.ToggleOn)))
    assertFalse(soft.contentEquals(synthesizeCue(SoundVoice.Digital, FeedbackCue.ToggleOn)))
    assertFalse(soft.contentEquals(synthesizeCue(SoundVoice.Soft, FeedbackCue.ToggleOff)))
  }

  @Test
  fun cardStylesPickTheirVoice() {
    assertEquals(SoundVoice.Mechanical, HaUberTheme.Console.soundVoice())
    assertEquals(SoundVoice.Digital, HaUberTheme.Lcd.soundVoice())
    assertEquals(SoundVoice.Digital, HaUberTheme.Trinitron.soundVoice())
    assertEquals(SoundVoice.Soft, HaUberTheme.Classic.soundVoice())
  }
}
