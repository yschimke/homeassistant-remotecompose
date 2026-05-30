# FirebaseCrashReporter is instantiated only via reflection
# (Class.forName in CrashReporter.create), so R8 in minified
# Crashlytics-enabled builds would otherwise strip or rename it —
# silently falling back to NoopCrashReporter and disabling crash
# reporting in exactly the release/experimental builds that need it.
# Keep the class name and its no-arg constructor. This file is only
# wired into the build when the crashlytics source set is compiled
# (see app/build.gradle.kts `crashlyticsEnabled`).
-keep class ee.schimke.terrazzo.crash.FirebaseCrashReporter {
    <init>();
}

# Crashlytics pulls in firebase-sessions, which adds androidx.window:window
# as a *direct* dependency. That makes androidx.window.layout.adapter.* live,
# and R8 then sees its references to the OEM window-extension classes
# (androidx.window.extensions.** / androidx.window.sidecar.**) that are
# provided by the system at runtime and never bundled — a hard "Missing
# classes" R8 error. These rules only matter in the Crashlytics-enabled
# release build (the non-Crashlytics build never resolves window directly),
# which is why they live here rather than in an always-applied proguard file.
-dontwarn androidx.window.extensions.WindowExtensions
-dontwarn androidx.window.extensions.WindowExtensionsProvider
-dontwarn androidx.window.extensions.area.ExtensionWindowAreaPresentation
-dontwarn androidx.window.extensions.core.util.function.Consumer
-dontwarn androidx.window.extensions.core.util.function.Function
-dontwarn androidx.window.extensions.core.util.function.Predicate
-dontwarn androidx.window.extensions.layout.DisplayFeature
-dontwarn androidx.window.extensions.layout.FoldingFeature
-dontwarn androidx.window.extensions.layout.WindowLayoutComponent
-dontwarn androidx.window.extensions.layout.WindowLayoutInfo
-dontwarn androidx.window.sidecar.SidecarDeviceState
-dontwarn androidx.window.sidecar.SidecarDisplayFeature
-dontwarn androidx.window.sidecar.SidecarInterface$SidecarCallback
-dontwarn androidx.window.sidecar.SidecarInterface
-dontwarn androidx.window.sidecar.SidecarProvider
-dontwarn androidx.window.sidecar.SidecarWindowLayoutInfo
