# Terrazzo for iOS

The iOS app mirrors the Android dashboard-first experience in SwiftUI while rendering every
Remote Compose card with the **pure Swift/UIKit player** from
[`yschimke/rc-players`](https://github.com/yschimke/rc-players):
`RcNativePlayerUIKit` 1.68.0.

## Open and run

1. Open `ios/Terrazzo.xcodeproj` on an Apple-silicon Mac.
2. Select an arm64 iPhone simulator or device.
3. Run the `Terrazzo` scheme.

Pass `--demo` as a launch argument to open the bundled dashboard directly (useful for screenshots
and UI smoke tests).

`Packages/RcNativePlayerUIKit` is the official self-contained source package attached to the
`rc-players` 1.68.0 release (including its license and profile). Keeping that small package in-tree
avoids SwiftPM downloading the unrelated 216 MB Compose XCFramework binary target.

## Data contract

Tap **Try demo mode** to run entirely from the bundled `.rc` documents. For a live deployment,
enter the base URL of a Remote Compose gateway and, optionally, its bearer token. The app requests:

```text
GET  /v1/apple/manifest.json
WS   /v1/stream
```

The manifest is versioned and currently contains URLs for already-encoded cards. This gateway is a
temporary `CardDocumentGenerator` implementation while AndroidX Remote Compose authoring remains
Android-only. The intended shipping implementation runs the KMP converter on-device and hands its
bytes to the same native player; SwiftUI does not depend on where those bytes were generated.

```json
{
  "version": 1,
  "dashboards": [{
    "id": "home",
    "title": "Home",
    "sections": [{
      "id": "living-room",
      "title": "Living room",
      "cards": [{
        "id": "light.kitchen",
        "type": "tile",
        "title": "Kitchen",
        "document_url": "/v1/cards/kitchen.rc?w=780&h=180&density=3&profile=phone",
        "height": 72,
        "entities": ["light.kitchen"],
        "update_mode": "named_bindings"
      }]
    }]
  }]
}
```

The stream follows the add-on's existing `/v1/stream` protocol. The app sends
`{"type":"subscribe","entities":[...]}` and applies scalar values from `bindings` directly to
the native player's named values. This is the live-card update path: cards are not frozen at the
first frame. `lovelace_updated` causes a manifest/document refresh.

Cards whose converter uses a `dataSignature` declare `"update_mode":"document"`. When one of
their signatures changes, the temporary gateway emits
`{"type":"documents_changed","card_ids":[...]}`; the app regenerates only those documents and
updates the cache. The future in-process KMP generator drives the same invalidation path locally.

Remote Compose `ha` actions are decoded by the host. Toggle and call-service actions go back over
the same stream; URL actions open through the system. Demo documents remain read-only.

Successful manifests and card documents are written atomically under Application Support. A cold
launch can therefore reopen the last dashboard without a network, matching the Android
offline-first contract. Bearer credentials are stored separately in the iOS Keychain.

> The repository's current `addon-server` still returns 501 for `.rc` generation. Demo mode is
> immediately usable; a live gateway must implement the contract above until the server-side JVM
> encoder milestone lands.

The bundled demo is also the temporary local-generator implementation: six recorded, real `.rc`
documents exercise the exact `CardDocumentGenerator` seam that the future KMP converter will fill.

## Release assets

Each project release publishes three Apple artifacts on the GitHub release page:

- `terrazzo-ios-<version>-unsigned.xcarchive.zip` — device archive for downstream signing;
- `terrazzo-ios-simulator-<version>.app.zip` — directly installable with `simctl`;
- `TerrazzoKit-<version>.xcframework.zip` — the KMP models, HA client, and generator interfaces.

Apple assets are accompanied by `SHA256SUMS-apple.txt`. The device archive is intentionally
unsigned until App Store signing and distribution credentials are configured.
