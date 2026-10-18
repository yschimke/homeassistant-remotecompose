# Android card planning evidence

Captured from the production `DashboardPlaceholders` composable by
`DashboardPlaceholdersTest`, using native Robolectric graphics at 360 × 800 pixels on API 35.
These screenshots show the new planning section before adding a brief, the filled form, and the
saved brief. The surrounding HA dashboard is omitted by the test host. All entities and dashboard
config in the test are fixtures; no real Home Assistant instance is contacted.

- [Before adding a brief](before-add.png)
- [Filled inline form](form.png)
- [Saved brief](after-add.png)

The interaction test adds, edits and removes a brief, copies the implementation prompt through the
Android clipboard, checks its contents, and asserts zero HA dashboard writes. Store tests cover
restart persistence, updates, deletion, and isolation by HA server/dashboard. Shared-client tests
cover the brief codec/validation, complete dashboard context, selected sources beyond the entity
inventory cap, unresolved sources and the live binding implementation contract.

Validation:

```sh
./gradlew ktfmtFormat ktfmtCheck :ha-client:jvmTest :app:testDebugUnitTest \
  --tests '*PlaceholderCardStoreTest' --tests '*DashboardPlaceholdersTest' \
  --tests '*DemoImageFetcherTest' --tests '*WidgetImagesTargetTest'
```

Result: 71 shared-client tests and 10 selected Android unit tests passed. Android application and
test sources compiled. No physical-device or emulator run was performed.
