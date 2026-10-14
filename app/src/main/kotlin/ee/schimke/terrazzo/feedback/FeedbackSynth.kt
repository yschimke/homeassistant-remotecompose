package ee.schimke.terrazzo.feedback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * How the interface sounds, by card style. Every sound is synthesised here from a few lines of
 * maths — there are no sample files, so nothing to license, and each is a few kilobytes of PCM.
 */
enum class SoundVoice {
  /** A soft, high tick: the default for the flat card styles. */
  Soft,
  /** A mechanical switch — a contact snap with its bounce and a small body resonance (Console). */
  Mechanical,
  /** A piezo beep, like a digital watch or a hi-fi remote (LCD, Trinitron). */
  Digital,
}

/** Sample rate every cue is synthesised at. */
const val FeedbackSampleRate = 44_100

/** The loudest any cue peaks at, as a fraction of full scale: interface sounds stay subtle. */
const val FeedbackPeak = 0.3f

/**
 * Mono 16-bit PCM for [cue] in [voice], at [FeedbackSampleRate], peaking at [FeedbackPeak].
 * Deterministic: the same cue always sounds the same.
 */
fun synthesizeCue(voice: SoundVoice, cue: FeedbackCue): ShortArray {
  val out = FloatArray(samples(LengthSeconds))
  val random = Random(voice.ordinal * 31 + cue.ordinal)
  when (voice) {
    SoundVoice.Soft ->
      when (cue) {
        FeedbackCue.ToggleOn -> out.tick(0f, 2200f, 0.004f)
        FeedbackCue.ToggleOff -> out.tick(0f, 1500f, 0.004f)
        FeedbackCue.Press -> out.tick(0f, 1800f, 0.005f)
        FeedbackCue.Key -> out.tick(0f, 2600f, 0.0025f, gain = 0.7f)
        FeedbackCue.Confirm -> {
          out.tick(0f, 1800f, 0.006f)
          out.tick(0.06f, 2700f, 0.008f)
        }
        FeedbackCue.Reject -> {
          out.tick(0f, 330f, 0.018f)
          out.tick(0.09f, 330f, 0.018f)
        }
      }
    SoundVoice.Mechanical ->
      when (cue) {
        // A toggle's contacts snap, then bounce once a few milliseconds later.
        FeedbackCue.ToggleOn -> {
          out.snap(0f, 1400f, random)
          out.snap(0.009f, 1500f, random, gain = 0.45f)
        }
        FeedbackCue.ToggleOff -> {
          out.snap(0f, 1050f, random)
          out.snap(0.011f, 1100f, random, gain = 0.45f)
        }
        FeedbackCue.Press -> out.snap(0f, 1800f, random, gain = 0.8f)
        FeedbackCue.Key -> out.snap(0f, 2300f, random, gain = 0.55f)
        // A relay pulling in: the armature's thunk, then its contacts.
        FeedbackCue.Confirm -> {
          out.snap(0f, 700f, random)
          out.snap(0.03f, 1700f, random, gain = 0.7f)
        }
        FeedbackCue.Reject -> {
          out.thud(0f, random)
          out.thud(0.1f, random)
        }
      }
    SoundVoice.Digital ->
      when (cue) {
        FeedbackCue.ToggleOn -> out.beep(0f, 0.03f, 4000f)
        FeedbackCue.ToggleOff -> out.beep(0f, 0.03f, 3000f)
        FeedbackCue.Press -> out.beep(0f, 0.02f, 4000f)
        FeedbackCue.Key -> out.beep(0f, 0.015f, 4000f)
        FeedbackCue.Confirm -> {
          out.beep(0f, 0.03f, 4000f)
          out.beep(0.07f, 0.03f, 4000f)
        }
        FeedbackCue.Reject -> out.beep(0f, 0.12f, 1000f)
      }
  }
  return out.toPcm()
}

private const val LengthSeconds = 0.25f

private fun samples(seconds: Float) = (seconds * FeedbackSampleRate).roundToInt()

/** A sine at [hz] that starts at [at] and dies away with time constant [tau]. */
private fun FloatArray.tick(at: Float, hz: Float, tau: Float, gain: Float = 1f) {
  val start = samples(at)
  val n = minOf(size - start, samples(tau * 8f))
  for (i in 0 until n) {
    val t = i.toFloat() / FeedbackSampleRate
    this[start + i] += gain * exp(-t / tau) * sin(2 * PI * hz * t).toFloat()
  }
}

/**
 * A mechanical snap at [at]: a very short burst of darkened noise (the contact) over two damped
 * body modes, the second at an inharmonic 2.7 × [hz], the way a small plastic or metal part rings.
 */
private fun FloatArray.snap(at: Float, hz: Float, random: Random, gain: Float = 1f) {
  val start = samples(at)
  val n = minOf(size - start, samples(0.04f))
  var lowPassed = 0f
  for (i in 0 until n) {
    val t = i.toFloat() / FeedbackSampleRate
    lowPassed += 0.35f * ((random.nextFloat() * 2f - 1f) - lowPassed)
    val contact = lowPassed * exp(-t / 0.0012f)
    val body =
      0.5f * exp(-t / 0.006f) * sin(2 * PI * hz * t).toFloat() +
        0.3f * exp(-t / 0.003f) * sin(2 * PI * hz * 2.7f * t).toFloat()
    this[start + i] += gain * (contact + body)
  }
}

/** A dull, low knock at [at]: a 140 Hz body and a little noise. */
private fun FloatArray.thud(at: Float, random: Random) {
  val start = samples(at)
  val n = minOf(size - start, samples(0.08f))
  for (i in 0 until n) {
    val t = i.toFloat() / FeedbackSampleRate
    val body = exp(-t / 0.02f) * sin(2 * PI * 140.0 * t).toFloat()
    val noise = 0.2f * exp(-t / 0.004f) * (random.nextFloat() * 2f - 1f)
    this[start + i] += body + noise
  }
}

/**
 * A piezo beep from [at] for [length]: a sine with a touch of third harmonic for the buzzer's edge,
 * faded in and out over 2 ms so it doesn't click.
 */
private fun FloatArray.beep(at: Float, length: Float, hz: Float) {
  val start = samples(at)
  val n = minOf(size - start, samples(length))
  val fade = samples(0.002f)
  for (i in 0 until n) {
    val t = i.toFloat() / FeedbackSampleRate
    val edge = minOf(1f, i.toFloat() / fade, (n - i).toFloat() / fade)
    val tone = sin(2 * PI * hz * t).toFloat() + 0.25f * sin(2 * PI * hz * 3 * t).toFloat()
    this[start + i] += 0.5f * edge * tone
  }
}

/** Normalise to [FeedbackPeak], trim the silent tail, and convert to 16-bit PCM. */
private fun FloatArray.toPcm(): ShortArray {
  val peak = maxOf(1e-6f, maxOf { kotlin.math.abs(it) })
  val last = indexOfLast { kotlin.math.abs(it) > peak * 0.002f }.coerceAtLeast(0)
  val scale = FeedbackPeak / peak * Short.MAX_VALUE
  return ShortArray(last + 1) { (this[it] * scale).roundToInt().toShort() }
}
