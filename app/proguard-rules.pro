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
