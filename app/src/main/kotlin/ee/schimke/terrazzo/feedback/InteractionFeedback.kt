package ee.schimke.terrazzo.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import ee.schimke.ha.rc.components.HaUberTheme
import ee.schimke.terrazzo.LocalTerrazzoGraph
import ee.schimke.terrazzo.ui.LocalCardStyle

/**
 * Haptics and quiet sounds for card actions. [play] is cheap and safe to call from the main thread:
 * haptics go through [View.performHapticFeedback] (so the system's touch-feedback setting still
 * wins), and each cue's PCM is synthesised once ([synthesizeCue]) into a static [AudioTrack] that
 * is replayed from the start.
 */
class InteractionFeedback(
  private val context: Context,
  private val view: View,
  private val haptics: () -> Boolean,
  private val sounds: () -> Boolean,
  private val voice: () -> SoundVoice,
) {
  private val tracks = HashMap<Pair<SoundVoice, FeedbackCue>, AudioTrack>()

  fun play(cue: FeedbackCue) {
    if (haptics()) view.performHapticFeedback(cue.hapticConstant())
    if (sounds() && ringerAllowsSound()) playSound(voice(), cue)
  }

  private fun ringerAllowsSound(): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
  }

  private fun playSound(voice: SoundVoice, cue: FeedbackCue) {
    runCatching {
      val track = tracks.getOrPut(voice to cue) { staticTrack(synthesizeCue(voice, cue)) }
      if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
      track.reloadStaticData()
      track.play()
    }
      .onFailure { Log.w(TAG, "Could not play $cue", it) }
  }

  private fun staticTrack(pcm: ShortArray): AudioTrack =
    AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(FeedbackSampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(pcm.size * 2)
      .build()
      .apply { write(pcm, 0, pcm.size) }

  fun release() {
    tracks.values.forEach { it.release() }
    tracks.clear()
  }

  private companion object {
    const val TAG = "InteractionFeedback"
  }
}

private fun FeedbackCue.hapticConstant(): Int =
  when (this) {
    FeedbackCue.ToggleOn -> HapticFeedbackConstants.TOGGLE_ON
    FeedbackCue.ToggleOff -> HapticFeedbackConstants.TOGGLE_OFF
    FeedbackCue.Press -> HapticFeedbackConstants.VIRTUAL_KEY
    FeedbackCue.Key -> HapticFeedbackConstants.KEYBOARD_TAP
    FeedbackCue.Confirm -> HapticFeedbackConstants.CONFIRM
    FeedbackCue.Reject -> HapticFeedbackConstants.REJECT
  }

/** The sound a card style makes: its instruments decide whether it clicks, beeps or ticks. */
fun HaUberTheme.soundVoice(): SoundVoice =
  when (id) {
    HaUberTheme.Console.id -> SoundVoice.Mechanical
    HaUberTheme.Lcd.id,
    HaUberTheme.Trinitron.id -> SoundVoice.Digital
    else -> SoundVoice.Soft
  }

/**
 * The [InteractionFeedback] for this screen, following the user's haptics and sound settings and
 * the current card style.
 */
@Composable
fun rememberInteractionFeedback(): InteractionFeedback {
  val context = LocalContext.current
  val view = LocalView.current
  val prefs = LocalTerrazzoGraph.current.preferencesStore
  val haptics = prefs.haptics.collectAsState(initial = true)
  val sounds = prefs.interfaceSounds.collectAsState(initial = false)
  val voice = rememberUpdatedState(LocalCardStyle.current.soundVoice())
  val feedback =
    remember(context, view) {
      InteractionFeedback(
        context.applicationContext,
        view,
        haptics = { haptics.value },
        sounds = { sounds.value },
        voice = { voice.value },
      )
    }
  DisposableEffect(feedback) { onDispose { feedback.release() } }
  return feedback
}
