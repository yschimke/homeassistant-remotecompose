package ee.schimke.terrazzo.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ee.schimke.terrazzo.shared.DesktopAgentGrants
import java.awt.Toolkit

/** The X11 WM_CLASS, matched by `StartupWMClass` in the Linux packages' `terrazzo.desktop`. */
private const val WM_CLASS = "terrazzo"

fun main() {
  nameX11WindowClass()
  Runtime.getRuntime().addShutdownHook(Thread { dev.datlag.kcef.KCEF.disposeBlocking() })
  // A connected agent and running sessions survive closing the app.
  DesktopAgentGrants.use()
  val icon =
    BitmapPainter(
      checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("terrazzo.png"))
        .use(::loadImageBitmap),
    )
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "Terrazzo",
      icon = icon,
      state = rememberWindowState(width = 1200.dp, height = 850.dp),
    ) {
      DesktopApp()
    }
  }
}

/**
 * AWT names every window's class after the main class (`ee-schimke-terrazzo-desktop-MainKt`), so
 * docks and taskbars can't match the window to its launcher. Must run before the first window.
 */
private fun nameX11WindowClass() {
  val toolkit = Toolkit.getDefaultToolkit()
  if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
  toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
    isAccessible = true
    set(toolkit, WM_CLASS)
  }
}
