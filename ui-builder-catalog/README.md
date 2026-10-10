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
packaged. The independent Android host below now proves executable delivery; a general IDE installation UI is still separate work. The existing sticker-sheet catalog and
its live preview bundles are not replaced by this opt-in proof. General event capabilities and
omitted-property default/export parity retain the limits documented in the authoring API.

The committed `evidence/` images show the direct native call and the typed adapter from the parity
test. Regenerate them from `build/renders` after component changes; CI uploads current renders.

## Independent Android host and public publication

The initial catalog can be packaged without converting the app components into desktop replicas:

```sh
./gradlew -PtypedAdapterCatalog=true -PlocalBuilds=tools \
  -PlocalBuild.tools=../compose-ai-tools -PuiBuilderSource=../compose-ui-builder \
  :ui-builder-catalog:unpackAndroidCatalogBundle
```

The ZIP contains the standard pair, a saved `heading.uid` design, and all three app library JARs and
AARs. Its provider is registered using JVM service metadata. The host supplies matching Android
Compose and Remote Compose engines; Android resources in the AARs remain available for packaging.
The prototype heading/stack proof does not dynamically install arbitrary Android resources.

`:ui-builder-catalog-host` has no dependency on Home Assistant modules. It checks app classes are
absent from the host, loads them from the installed JARs, draws the real Remote Compose heading and
visible stack child, changes the title, saves/reopens, and compares the reopened pixels. The scalar
heading wrapper recaptures its Remote Compose document when the title changes, so edits appear
immediately. The generic renderer is UI Builder's existing document host and registry interpreter.

Publish after validation using the explicit manual workflow input:

```sh
gh workflow run typed-catalog.yml --ref YOUR_SOURCE_BRANCH -f publish=true
```

A separate job publishes the tested CI artifact as `typed-catalog-preview-FULL_SOURCE_SHA`, with
standard catalog files, checksums and full app/tools/builder provenance. Existing generations are
never replaced; ordinary PR runs remain read-only and production delivery remains unchanged.

From the matching UI Builder checkout, fetch and verify the public artifact:

```sh
scripts/download-typed-catalog.sh yschimke/homeassistant-remotecompose FULL_SOURCE_SHA android /tmp/published-ha
```

Then run the independent host with `-PtypedCatalogAndroidBundle=/tmp/published-ha` and
`:ui-builder-catalog-host:testDebugUnitTest`. Passing an external directory does not build or
package app libraries. The install directory must be new; the downloader refuses a wrong source,
runtime, checksum or JSON pair. Executable bundles require explicit trust in the publisher.
