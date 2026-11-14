package ee.schimke.terrazzo.core.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException

/**
 * Whether [this], thrown while reading a value stored as AES-GCM ciphertext under an
 * AndroidKeyStore key, means that value can never be read on this install, so the caller should
 * forget it rather than fail.
 *
 * The common case is a restore from backup or a device-to-device transfer: the ciphertext comes
 * back but the Keystore key never does (Keystore keys are not exportable). The store then creates a
 * fresh key, and GCM refuses the old ciphertext with `AEADBadTagException`, a
 * [BadPaddingException]. Also covered: a key the OS invalidated
 * ([KeyPermanentlyInvalidatedException], [UnrecoverableKeyException]) and stored text that isn't
 * valid Base64 ([IllegalArgumentException]).
 *
 * Anything else (a Keystore that is momentarily unavailable, say) is not treated as unreadable: it
 * could succeed on a later attempt, and dropping the value would sign the user out for nothing.
 */
fun Throwable.isUnreadableCiphertext(): Boolean =
  this is BadPaddingException ||
    this is KeyPermanentlyInvalidatedException ||
    this is UnrecoverableKeyException ||
    this is IllegalArgumentException
