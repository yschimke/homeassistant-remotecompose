@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.terrazzo.shared

// Suppress only the browser menu belonging to a handled entity secondary press.
@JsFun(
  """() => {
  const listener = event => { event.preventDefault(); };
  document.addEventListener('contextmenu', listener, {once: true});
  setTimeout(() => document.removeEventListener('contextmenu', listener), 1000);
}"""
)
internal actual external fun suppressNextEntityContextMenu()
