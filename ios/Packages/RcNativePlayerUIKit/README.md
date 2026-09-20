# RcNativePlayerUIKit release package

This archive is the self-contained, experimental UIKit Remote Compose player for iOS. Its decoder,
retained state, component hierarchy, layout, and drawing are pure Swift, with no Kotlin framework or
binary-target dependency. The separate CMP product remains available from the repository package.

Add the extracted directory as a local Swift package and select the `RcNativePlayerUIKit` product.
Hosts that only need the renderer-neutral decoder and retained session may select
`RcNativePlayerCore`; that product supports iOS 13 and macOS 12 or newer. The UIKit product supports
iOS 13 or newer on arm64 devices and Apple-silicon simulators. It includes
retained state, named values and click actions, UIKit accessibility semantics, and demand-driven
animation for the operation families declared in `PROFILE.json`. It is not a claim of full CMP
compatibility; unlisted behavior remains outside the native profile.

The archive also includes the optional `RcPlayerAppleFonts` product. Add that product to the host
target and pass its `RemoteComposeGoogleFontsResolver` to the UIKit player to opt into downloading
`google:` families. If no resolver is configured, those families render with the normal system-font
fallback and the document continues loading.

For normal versioned SwiftPM consumption, use the repository's bare semantic-version tag and select
the same product. This archive is intended for pinned evaluation, CI fixtures, and consumers that
want one downloadable bundle with no binary fetch during package resolution.

`PROFILE.json` records the exact release version, source revision, platform matrix, capability
claim, verified fixtures, default execution limits, and migration policy for this archive. Verify
the archive and profile sidecars from the same GitHub Release before using it.
