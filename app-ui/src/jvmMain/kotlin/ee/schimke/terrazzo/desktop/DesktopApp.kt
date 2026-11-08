package ee.schimke.terrazzo.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ee.schimke.ha.client.HaConfig
import ee.schimke.terrazzo.shared.DesktopCommands
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp
import java.net.BindException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DesktopApp(
  desktopCommands: DesktopCommands? = null,
  isFullScreen: Boolean = false,
  onToggleFullScreen: (() -> Unit)? = null,
) {
  val auth = remember { DesktopHomeAssistantAuth() }
  val session by auth.session.collectAsState()
  val scope = rememberCoroutineScope()
  var restoring by remember { mutableStateOf(true) }
  var signingIn by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var signInJob by remember { mutableStateOf<Job?>(null) }
  DisposableEffect(auth) { onDispose { auth.close() } }
  LaunchedEffect(auth) {
    try {
      auth.restore()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      error = "Could not restore your saved sign-in. Check your network or sign in again."
    } finally {
      restoring = false
    }
  }
  if (restoring) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator()
      Text("Restoring sign-in…")
    }
    return
  }
  // A new OAuth session replaces the login form; sign-out or expiry returns to a fresh form.
  key(session) {
    TerrazzoMultiplatformApp(
      desktopCommands = desktopCommands,
      isFullScreen = isFullScreen,
      onToggleFullScreen = onToggleFullScreen,
      hostedConfig = session?.let { HaConfig(it.server, "") },
      hostedAccessToken = session?.let { { auth.currentAccessToken() } },
      signInWithHomeAssistant = { server ->
        if (!signingIn && !restoring) {
          signingIn = true
          error = null
          signInJob = scope.launch {
            try {
              auth.signIn(server)
            } catch (_: TimeoutCancellationException) {
              error = "Sign-in timed out. Try again."
            } catch (e: CancellationException) {
              throw e
            } catch (_: BindException) {
              error =
                "The sign-in port is in use. Close other Terrazzo sign-in windows and try again."
            } catch (_: DesktopCredentialStoreUnavailable) {
              error =
                "Could not save your sign-in. Unlock your device's credential store and try again, or use a token under Advanced options."
            } catch (_: Exception) {
              error =
                "Could not sign in. Check your browser, server address, and network, then try again."
            } finally {
              signingIn = false
            }
          }
        }
      },
      signInBusy = signingIn,
      signInError = error,
      onCancelSignIn = if (signingIn) ({ signInJob?.cancel() }) else null,
      onSignOut = { if (session != null) auth.signOut() },
      signInStorageLabel =
        session?.let { "Your Home Assistant sign-in is saved in this device's credential store." },
    )
  }
}
