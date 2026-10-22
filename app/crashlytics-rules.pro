# FirebaseCrashReporter is instantiated only via reflection
# (Class.forName in CrashReporter.create), so R8 in minified
# Crashlytics-enabled builds would otherwise strip or rename it —
# silently falling back to NoopCrashReporter and disabling crash
# reporting in exactly the release/experimental builds that need it.
# Keep the class name and its no-arg constructor. This file is only
# wired into the build when the crashlytics source set is compiled
# (see the `harc.crashReporter` selector in app/build.gradle.kts).
-keep class ee.schimke.terrazzo.crash.FirebaseCrashReporter {
    <init>();
}
