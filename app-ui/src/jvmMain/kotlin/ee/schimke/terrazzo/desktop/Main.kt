package ee.schimke.terrazzo.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import ee.schimke.terrazzo.shared.DesktopAgentGrants
import ee.schimke.terrazzo.shared.DesktopCommands
import ee.schimke.terrazzo.shared.TerrazzoMultiplatformApp
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.desktop.AppReopenedListener

/** The X11 WM_CLASS, matched by `StartupWMClass` in the Linux packages' `terrazzo.desktop`. */
private const val WM_CLASS = "terrazzo"

fun main() {
  val isMac = System.getProperty("os.name").contains("Mac")
  if (isMac) {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Terrazzo")
  }
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
    val commands = remember { DesktopCommands() }
    val state = rememberWindowState(width = 1200.dp, height = 850.dp)
    var visible by remember { mutableStateOf(true) }
    var beforeFullscreen by remember { mutableStateOf(WindowPlacement.Floating) }
    var beforeFullscreenSize by remember { mutableStateOf(state.size) }
    var beforeFullscreenPosition by remember { mutableStateOf(state.position) }
    fun toggleFullscreen() {
      if (state.placement == WindowPlacement.Fullscreen) {
        state.placement = beforeFullscreen
        state.size = beforeFullscreenSize
        state.position = beforeFullscreenPosition
      } else {
        beforeFullscreen = state.placement
        beforeFullscreenSize = state.size
        beforeFullscreenPosition = state.position
        state.placement = WindowPlacement.Fullscreen
      }
    }
    Window(
      onCloseRequest = { if (isMac) visible = false else exitApplication() },
      title = "Terrazzo",
      icon = icon,
      state = state,
      visible = visible,
    ) {
      DisposableEffect(window) {
        window.minimumSize = Dimension(800, 600)
        val desktop = if (isMac && Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        val reopen = AppReopenedListener {
          visible = true
          state.isMinimized = false
          window.toFront()
        }
        desktop?.addAppEventListener(reopen)
        if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true) {
          desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            exitApplication()
          }
        }
        onDispose {
          desktop?.removeAppEventListener(reopen)
          if (desktop?.isSupported(Desktop.Action.APP_QUIT_HANDLER) == true)
            desktop.setQuitHandler(null)
        }
      }
      MenuBar {
        Menu("Dashboard", mnemonic = 'D') {
          Item(
            "Home",
            enabled = commands.canNavigate,
            shortcut = KeyShortcut(Key.One, ctrl = !isMac, meta = isMac),
            onClick = commands::home,
          )
          Item(
            "Edit dashboard",
            enabled = commands.canEdit,
            shortcut = KeyShortcut(Key.E, ctrl = !isMac, meta = isMac),
            onClick = commands::edit,
          )
          Separator()
          Item(
            "Settings",
            enabled = commands.canNavigate,
            shortcut = KeyShortcut(Key.Comma, ctrl = !isMac, meta = isMac),
            onClick = commands::settings,
          )
        }
        Menu("Window", mnemonic = 'W') {
          CheckboxItem(
            "Full Screen",
            checked = state.placement == WindowPlacement.Fullscreen,
            shortcut =
              if (isMac) KeyShortcut(Key.F, ctrl = true, meta = true) else KeyShortcut(Key.F11),
            onCheckedChange = { toggleFullscreen() },
          )
          Item(
            "Minimize",
            shortcut = if (isMac) KeyShortcut(Key.M, meta = true) else null,
            onClick = { state.isMinimized = true },
          )
          if (!isMac) {
            Separator()
            Item("Quit", shortcut = KeyShortcut(Key.Q, ctrl = true), onClick = ::exitApplication)
          }
        }
      }
      TerrazzoMultiplatformApp(
        desktopCommands = commands,
        isFullScreen = state.placement == WindowPlacement.Fullscreen,
        onToggleFullScreen = ::toggleFullscreen,
      )
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
