# Rules for every minified build of the app, whichever crash reporter is
# compiled in (backend-specific rules live in crashlytics-rules.pro).

# Enums restored from saved state. `rememberSaveable { mutableStateOf(SomeEnum.X) }`
# parcels the enum as a Serializable, and restoring it (rotation, or process
# death while sign-in is in a Custom Tab) goes through Enum.valueOf, which on
# Android looks up the enum's static `values()` reflectively. The build doesn't
# apply the SDK's default proguard-android-optimize.txt, so nothing kept that
# method and R8 removed it: the restore threw NoSuchMethodException and took
# the app down (Crashlytics, LoginControllerKt.rememberKeyedLauncher). This is
# the same rule the default file carries.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# androidx.window references the OEM window-extension classes (androidx.window.extensions.** /
# androidx.window.sidecar.**), which the system provides at runtime and no build bundles: an R8
# "Missing classes" error wherever window is on the classpath. That used to be only the
# Crashlytics build (firebase-sessions), so these lived in crashlytics-rules.pro; window now comes
# in without it too, and the benchmark build failed R8 on exactly these classes.
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
