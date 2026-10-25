package ee.schimke.terrazzo.notify

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * This install's FCM token, for the Terrazzo push relay ([PushRelay]). Instantiated reflectively,
 * so it keeps a no-arg constructor (and a keep rule in `crashlytics-rules.pro`).
 */
internal class FirebasePushTokens : PushTokenSource {
  override suspend fun token(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance()
      .token
      .addOnSuccessListener { cont.resumeWith(Result.success(it)) }
      .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
  }
}
