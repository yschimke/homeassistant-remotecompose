# Home Assistant typed component catalog

This opt-in module wires `HaVerticalStack` and a scalar `CatalogHeading` wrapper that invokes the real `HaHeading` / Remote Compose player into the typed UI-builder adapter API. It is excluded from
normal application builds while the API and bridge are unreleased. Each definition publishes its
own ID and properties/slots, and the registry invokes the actual component with compiled calls.

Check out compose-ai-tools and compose-ui-builder beside this repository at the immutable revisions
in `.github/workflows/typed-catalog.yml`. The workflow validates the same source integration on
catalog/component/dependency changes. Existing public preview publication remains on released tools.

```sh
scripts/validate-typed-catalog.sh
```

The script accepts optional tools and builder checkout paths. It scans current compiled app classes,
generates only explicitly selected records, checks JSON readback and stale-record refusal, then
compares the actual Android Remote Compose heading with its typed adapter pixel for pixel and checks children delivered to the real stack slot. A separate builder process reads the resulting pair through
`PublishedUiBuilderCatalog` with no app implementation on its classpath. PNG comparisons are saved
under `ui-builder-catalog/build/renders`; neither rendering test connects to a real device or server.

To generate the standard pair directly:

```sh
./gradlew -PtypedAdapterCatalog=true -PlocalBuilds=tools \
  -PlocalBuild.tools=../compose-ai-tools -PuiBuilderSource=../compose-ui-builder \
  :ui-builder-catalog:exportTypedCatalog
```

Output: `ui-builder-catalog/build/catalog/testDebugUnitTest/components.json` and `ui-builder.json`.
The export task runs the contract and native render tests against current compiled classes. Its
Robolectric Android 35 runtime is a pinned Gradle input, with test-time downloads disabled. Task-owned previous output is
replaced on regeneration; the underlying pair writer refuses arbitrary existing destinations.

To stage this in a catalog delivery generation, copy both files together and set the delivery
`catalog.json` fields `componentsFile` and `uiBuilderFile` to those relative paths. Do not combine
records from one source revision with policy from another. Keep the executable renderer from the
same revision with the catalog when installing it in a native host. CI uploads the generated pair
and comparison renders as review artifacts; this module does not push a delivery branch.

These app libraries currently target Android/JVM, not Wasm. Records explicitly declare `nativeOnly`,
so a browser editor must show its existing native placeholder until a compatible runtime is
packaged. A general JVM/IDE loader is still separate work. The existing sticker-sheet catalog and
its live preview bundles are not replaced by this opt-in proof. General event capabilities and
omitted-property default/export parity retain the limits documented in the authoring API.

The committed `evidence/` images show the direct native call and the typed adapter from the parity
test. Regenerate them from `build/renders` after component changes; CI uploads current renders.
