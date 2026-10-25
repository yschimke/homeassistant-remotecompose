package ee.schimke.terrazzo.core.mobileapp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Vectors from PyNaCl's `SecretBox(key, encoder=HexEncoder).encrypt(msg, nonce)`, as HA reads them.
 */
@OptIn(ExperimentalEncodingApi::class)
class SecretBoxTest {
  private val key =
    SecretBox.keyFromHex("0f1e2d3c4b5a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0")
  private val nonce = ByteArray(24) { it.toByte() }

  private fun sealed(message: String) =
    Base64.encode(SecretBox.seal(key, nonce, message.encodeToByteArray()))

  @Test
  fun anEmptyMessageIsJustTheNonceAndTag() {
    assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXv4Dm0bpVhN7z1707ATbp7g==", sealed(""))
  }

  @Test
  fun aShortMessageMatchesPyNaCl() {
    assertEquals(
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXsEHQqrEYXHjNyJrSiqNXZEYbw5qsUUU=",
      sealed("{\"a\":1}"),
    )
  }

  @Test
  fun aMessageSpanningSeveralBlocksMatchesPyNaCl() {
    assertEquals(
      "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXVFsPIii+gXsMH/DMGqunB0VB2sDuGEC4oh0gKeri3K2jtZCIMSPHZuJl" +
        "cN2h8pSGEZlWkYxrfy9OjpPgP5k3lGNKeCHAH14cRJ/uCNtvUzRwnF/hXZFwzDWnNRDuvg0XnE2gbGLjnfdzZaNW" +
        "RGAaZInPw4G2EV2Zkh6glAe+ZPg3nLuXWnx/pV/sfRjEmzMMxHyNeUoBLkVHD/jkNaon+lUWOApu3bj1HJDnNm+H" +
        "AmthAO27/tgQU4c8NepCYXseHfzOMSvOHj22cVswov2plzpYJ5Gh82lz",
      sealed("x".repeat(200)),
    )
  }

  /** Lengths either side of Poly1305's 16-byte and Salsa20's 64-byte blocks. */
  @Test
  fun blockBoundariesMatchPyNaCl() {
    val expected =
      mapOf(
        15 to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX6vvslB+SfLyME2kOi9wIFD4zs6CJRhX04ScRAcXEwQ==",
        16 to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXmjjHlL+SWZ2DK9FBIwDnij4zs6CJRhX04ScRAcXEwbk=",
        17 to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXRs9+3OYkzbkBMXCp6hW0ZD4zs6CJRhX04ScRAcXEwbmo",
        31 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXj/+1lMM4B2t74YX2an1saD4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDk=",
        32 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXc9mhDRq1GizXA0WfZW4wDT4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDki",
        33 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXIaSgIdsigLRH3rO8hRbaLD4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDkiig==",
        63 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXRibfEfWQIA1di2kulaUkDz4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDkiigvfEQsVCkMt1MKocN8KoEhoYTHXEVvgt3UP0AS5ng==",
        64 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXh6q0EakxGcpSpj5ooI3eND4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDkiigvfEQsVCkMt1MKocN8KoEhoYTHXEVvgt3UP0AS5nvA=",
        65 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYX9mZQ+zfyLOxOJ2vRwQKARz4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDkiigvfEQsVCkMt1MKocN8KoEhoYTHXEVvgt3UP0AS5nvDL",
        128 to
          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXhbWy0Bg1jIDuWbLHt94zlT4zs6CJRhX04ScRAcXEwbmot2l4xs0iujGvsWUeRDkiigvfEQsVCkMt1MKocN8KoEhoYTHXEVvgt3UP0AS5nvDLLvZB+g/lQLbdRHiB2FBD1w+ZXFXNuOtgb6Kuu5b3gFIdCkFxr+g1MYQxHIg4GYxc/uLHDTI6mWzGXADbjT4I",
      )
    for ((length, b64) in expected) {
      val message = ByteArray(length) { ((it * 7 + 3) % 256).toByte() }
      assertEquals(b64, Base64.encode(SecretBox.seal(key, nonce, message)), "length $length")
    }
  }

  @Test
  fun aSecretThatIsNotAKeyIsRefused() {
    assertFailsWith<IllegalArgumentException> { SecretBox.keyFromHex("abcd") }
  }
}
