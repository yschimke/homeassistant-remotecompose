# Desktop workspace

Run `./gradlew :app-ui:run` to open the JVM app on macOS, Linux, or Windows.
After connecting, the sidebar keeps the available Lovelace dashboards and Settings visible.
The existing dashboard switcher still works. While editing a dashboard or an agent draft,
sidebar navigation and the native navigation commands are disabled; finish or cancel the edit
through its existing controls before switching away.

The native **Dashboard** menu exposes Home (Command/Ctrl+1), Edit dashboard (Command/Ctrl+E),
and Settings (Command/Ctrl+,). **Window** offers full screen (Control+Command+F on macOS,
F11 elsewhere) and minimize. Full screen also works from the dashboard header.

On macOS, closing the window hides it while keeping the app running. Clicking the Dock icon
reopens it; Quit stops the app. Linux and Windows close the app when its window closes.
The desktop window starts at 1200×850 with an 800×600 minimum.

These commands and the sidebar are enabled only by the JVM host. Browser, panel and Android
hosts keep their existing navigation. Native macOS menu/Dock behavior and Wayland need platform
verification; Linux X11 screenshots and reproduction details accompany the PR.
