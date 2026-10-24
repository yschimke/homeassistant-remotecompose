Run from the repository root with JDK 21:

```sh
./gradlew -p scripts/player-dependency-check check
```

This independent build resolves the real catalog's player dependencies for Android,
desktop, Wasm, and both supported iOS targets without requiring an Android SDK or
Xcode. It checks that each consumer imports the platform in its dependency scope,
that player aliases remain versionless, and that the standalone browser configuration
selects and downloads one valid ZIP through the same BOM.

Each selected player is checked against its own BOM constraint, including partial
releases where Kotlin modules or the JS distribution retain older versions.
