# Rules for every minified build of the watch app.

# Enums restored from saved state: `rememberSaveable { mutableStateOf(WearScreen.TopLevel) }`
# parcels the enum as a Serializable, and restoring it goes through Enum.valueOf, which looks up
# the enum's static `values()` reflectively. The build doesn't apply the SDK's default
# proguard-android-optimize.txt, so keep them here (same rule as app/proguard-rules.pro).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# androidx.window (via app-ui) references the OEM window-extension classes, which the system
# provides at runtime and no build bundles. Same list as app/proguard-rules.pro.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
