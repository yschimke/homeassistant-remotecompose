# Apple app boundaries

## Decision

The Apple app uses three implementation lanes deliberately:

| Concern | Implementation | Why |
| --- | --- | --- |
| App shell, navigation, authentication UI, discovery, Keychain | SwiftUI / Apple APIs | Platform behavior and accessibility are the feature. |
| Remote Compose playback | `RcNativePlayerUIKit` from `yschimke/rc-players` | Native UIKit/Core Graphics player, native controls and accessibility. |
| HA models, WebSocket protocol, dashboard/session rules | Kotlin Multiplatform | Existing tested logic is expensive to duplicate and has no platform UI value. |
| Lovelace card → `.rc` generation | KMP `CardGenerator` (target); recorded/gateway adapter (temporary) | Keep the intended on-device boundary in place while AndroidX authoring is still Android-only. |

Wrapping the current converters in an XCFramework is **not** viable. An XCFramework can package
Kotlin/Native code, but it cannot turn Android-only AAR dependencies into iOS klibs. The app still
treats on-device KMP generation as the shipping architecture: `CardGenerator` / `CardSource` are
common code, while a recorded-document generator and an optional gateway adapter temporarily
produce bytes. Once authoring publishes native variants, the real converter replaces those fakes
without changing the SwiftUI or player layers.

## Ktor on Apple

Networking stays shared. `ha-client` has `iosArm64` and `iosSimulatorArm64` targets and uses
`io.ktor:ktor-client-darwin`, which is backed by `NSURLSession` and supports both HTTP/2 and
WebSockets. This retains the HA authentication handshake, command correlation, reconnection, state
decoding, and add-on protocol in one implementation.

Apple-specific policy remains outside Ktor:

- `ASWebAuthenticationSession` owns the OAuth browser flow.
- Keychain owns refresh-token storage.
- `NWBrowser` owns Bonjour discovery and local-network permission UX.
- SwiftUI observes a narrow exported Kotlin facade rather than raw `Flow`, `JsonObject`, or Ktor
  engine types.

The exported facade should traffic in immutable DTOs and `suspend` functions. Swift callbacks or an
`AsyncStream` adapter bridge long-lived updates. Do not export `HttpClient`, `Flow`, or serializer
types as public Swift API.

## Live card contract

Each manifest card declares the entity IDs it needs. The shared client subscribes once, translates
state events into the converter's named-binding keys, and Swift calls the native player's typed
`setString`, `setFloat`, and `setColor` functions. Documents whose dynamic values cannot be
represented by named bindings are re-fetched after their data signature changes. This preserves
the same two update paths documented in `live-card-updates.md`.
