package ee.schimke.terrazzo.core.mobileapp

/**
 * NaCl's `crypto_secretbox` (XSalsa20-Poly1305), the encryption Home Assistant's `mobile_app`
 * requires on webhooks from a registration that negotiated `supports_encryption`. HA decrypts with
 * PyNaCl's `SecretBox(bytes.fromhex(secret))`; [seal] produces exactly PyNaCl's combined form,
 * `nonce || tag || ciphertext`, which HA reads base64-encoded from `encrypted_data`.
 *
 * Pure Kotlin, so it needs no native libsodium. Only sealing is needed: HA's encrypted replies are
 * not read.
 */
object SecretBox {
  const val KEY_SIZE = 32
  const val NONCE_SIZE = 24
  const val TAG_SIZE = 16

  /** Seals [message] under [key] with [nonce] (24 random bytes, never reused for the key). */
  fun seal(key: ByteArray, nonce: ByteArray, message: ByteArray): ByteArray {
    require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
    require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
    // XSalsa20: HSalsa20 derives a subkey from the nonce's first 16 bytes; Salsa20 runs under it
    // with the last 8.
    val subkey = hsalsa20(key, nonce.copyOfRange(0, 16))
    val stream = salsa20Stream(subkey, nonce.copyOfRange(16, 24), 32 + message.size)
    val ciphertext =
      ByteArray(message.size) { (message[it].toInt() xor stream[32 + it].toInt()).toByte() }
    val tag = poly1305(stream.copyOfRange(0, 32), ciphertext)
    return nonce + tag + ciphertext
  }

  /** Home Assistant's registration `secret` (64 hex characters) as the box key. */
  fun keyFromHex(secret: String): ByteArray {
    require(secret.length == KEY_SIZE * 2) { "secret must be ${KEY_SIZE * 2} hex characters" }
    return ByteArray(KEY_SIZE) { secret.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
  }

  private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

  private fun le32(b: ByteArray, i: Int): Int =
    (b[i].toInt() and 0xff) or
      ((b[i + 1].toInt() and 0xff) shl 8) or
      ((b[i + 2].toInt() and 0xff) shl 16) or
      ((b[i + 3].toInt() and 0xff) shl 24)

  private fun putLe32(out: ByteArray, i: Int, v: Int) {
    out[i] = v.toByte()
    out[i + 1] = (v ushr 8).toByte()
    out[i + 2] = (v ushr 16).toByte()
    out[i + 3] = (v ushr 24).toByte()
  }

  /** The Salsa20 state for [key] and the 16-byte [input] (nonce and counter). */
  private fun state(key: ByteArray, input: ByteArray): IntArray =
    intArrayOf(
      SIGMA[0],
      le32(key, 0),
      le32(key, 4),
      le32(key, 8),
      le32(key, 12),
      SIGMA[1],
      le32(input, 0),
      le32(input, 4),
      le32(input, 8),
      le32(input, 12),
      SIGMA[2],
      le32(key, 16),
      le32(key, 20),
      le32(key, 24),
      le32(key, 28),
      SIGMA[3],
    )

  private fun rounds(x: IntArray) {
    fun qr(a: Int, b: Int, c: Int, d: Int) {
      x[b] = x[b] xor (x[a] + x[d]).rotateLeft(7)
      x[c] = x[c] xor (x[b] + x[a]).rotateLeft(9)
      x[d] = x[d] xor (x[c] + x[b]).rotateLeft(13)
      x[a] = x[a] xor (x[d] + x[c]).rotateLeft(18)
    }
    repeat(10) {
      qr(0, 4, 8, 12)
      qr(5, 9, 13, 1)
      qr(10, 14, 2, 6)
      qr(15, 3, 7, 11)
      qr(0, 1, 2, 3)
      qr(5, 6, 7, 4)
      qr(10, 11, 8, 9)
      qr(15, 12, 13, 14)
    }
  }

  private fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
    val x = state(key, input)
    rounds(x)
    val out = ByteArray(32)
    intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, word -> putLe32(out, i * 4, x[word]) }
    return out
  }

  private fun salsa20Stream(key: ByteArray, nonce: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    val input = ByteArray(16)
    nonce.copyInto(input)
    var counter = 0L
    var offset = 0
    while (offset < length) {
      for (i in 0 until 8) input[8 + i] = (counter ushr (8 * i)).toByte()
      val initial = state(key, input)
      val x = initial.copyOf()
      rounds(x)
      val block = ByteArray(64)
      for (i in 0 until 16) putLe32(block, i * 4, x[i] + initial[i])
      val n = minOf(64, length - offset)
      block.copyInto(out, offset, 0, n)
      offset += n
      counter++
    }
    return out
  }

  /** Poly1305 one-time authenticator over [message] under the 32-byte [key] (26-bit limbs). */
  internal fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
    val mask = 0x3ffffffL
    fun u(i: Int, b: ByteArray = key): Long = le32(b, i).toLong() and 0xffffffffL
    val r0 = u(0) and 0x3ffffffL
    val r1 = (u(3) ushr 2) and 0x3ffff03L
    val r2 = (u(6) ushr 4) and 0x3ffc0ffL
    val r3 = (u(9) ushr 6) and 0x3f03fffL
    val r4 = (u(12) ushr 8) and 0x00fffffL
    val s1 = r1 * 5
    val s2 = r2 * 5
    val s3 = r3 * 5
    val s4 = r4 * 5
    var h0 = 0L
    var h1 = 0L
    var h2 = 0L
    var h3 = 0L
    var h4 = 0L
    var offset = 0
    val block = ByteArray(16)
    while (offset < message.size) {
      val n = minOf(16, message.size - offset)
      block.fill(0)
      message.copyInto(block, 0, offset, offset + n)
      // A full block's 2^128 bit; a short last block is padded with a 1 byte instead.
      val hibit = if (n == 16) 1L shl 24 else 0L
      if (n < 16) block[n] = 1
      h0 += u(0, block) and mask
      h1 += (u(3, block) ushr 2) and mask
      h2 += (u(6, block) ushr 4) and mask
      h3 += (u(9, block) ushr 6) and mask
      h4 += (u(12, block) ushr 8) or hibit
      val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
      var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
      var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
      var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
      var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
      var c = d0 ushr 26
      h0 = d0 and mask
      d1 += c
      c = d1 ushr 26
      h1 = d1 and mask
      d2 += c
      c = d2 ushr 26
      h2 = d2 and mask
      d3 += c
      c = d3 ushr 26
      h3 = d3 and mask
      d4 += c
      c = d4 ushr 26
      h4 = d4 and mask
      h0 += c * 5
      c = h0 ushr 26
      h0 = h0 and mask
      h1 += c
      offset += n
    }
    // Fully carry h, then reduce mod 2^130 - 5.
    var c = h1 ushr 26
    h1 = h1 and mask
    h2 += c
    c = h2 ushr 26
    h2 = h2 and mask
    h3 += c
    c = h3 ushr 26
    h3 = h3 and mask
    h4 += c
    c = h4 ushr 26
    h4 = h4 and mask
    h0 += c * 5
    c = h0 ushr 26
    h0 = h0 and mask
    h1 += c
    var g0 = h0 + 5
    c = g0 ushr 26
    g0 = g0 and mask
    var g1 = h1 + c
    c = g1 ushr 26
    g1 = g1 and mask
    var g2 = h2 + c
    c = g2 ushr 26
    g2 = g2 and mask
    var g3 = h3 + c
    c = g3 ushr 26
    g3 = g3 and mask
    val g4 = h4 + c - (1L shl 26)
    // h + 5 overflowed 2^130 (g4 >= 0): h was at least p, so h - p is the reduced value.
    if (g4 >= 0) {
      h0 = g0
      h1 = g1
      h2 = g2
      h3 = g3
      h4 = g4
    }
    val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
    val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
    val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
    val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
    var f = w0 + u(16)
    val out = ByteArray(16)
    putLe32(out, 0, f.toInt())
    f = w1 + u(20) + (f ushr 32)
    putLe32(out, 4, f.toInt())
    f = w2 + u(24) + (f ushr 32)
    putLe32(out, 8, f.toInt())
    f = w3 + u(28) + (f ushr 32)
    putLe32(out, 12, f.toInt())
    return out
  }
}
