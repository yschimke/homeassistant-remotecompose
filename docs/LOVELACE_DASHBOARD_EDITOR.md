# Dashboard editor

`/studio/dashboard` embeds the packaged Compose UI Builder and owns the Home Assistant session.
The host serves the editor at `/studio/dashboard/assets/` from `UI_BUILDER_WEB_DIR`. The image
sets that directory to its bundled preview-server archive's `ui-builder` folder, independently of
whether the builder process runs locally or the APIs point at the shared server. A plain JVM host
must unpack the matching released editor archive and set `UI_BUILDER_WEB_DIR` to it. This keeps
module, Wasm and resource requests on the host origin without requiring a reverse proxy.
Use **Load test dashboard** to edit a fixture without any HA connection. Import JSON creates a
local draft; export downloads the complete config. Supported sections views expose views, sections,
cards, editable card fields and grid sizes. Other view layouts remain opaque and are preserved.
Unknown custom cards remain in exported config even when the renderer cannot draw them.
Lossless numeric config requires a browser with JSON parse source context and `JSON.rawJSON`;
unsupported browsers report an error instead of rounding a dashboard during import or save.

Connect signs into Home Assistant using the existing embed OAuth client. Load selects a dashboard,
reads the current user's admin status and subscribes to entities used in its config. The host owns
all credentials and writes; UI Builder receives only config, capabilities and transient preview
bytes. The preview service requires editor authorization, independently of HA authorization. Standalone
hosts use the existing design-server editor authorization. On the split ha.coo.ee host, the
existing UI Builder identity endpoint verifies the shared sign-in cookie, which is forwarded to
the render endpoint without replacing it with the operator credential. An HA session does not
grant server compute access.

Save requires a storage dashboard and administrator rights. Before saving or undoing a save, the
host re-reads config and rejects an observed external change. Failed or conflicting writes retain
the draft. HA's WebSocket API has no atomic conditional save, so another client can still race
between read and write. Export/reload/reapply resolves a conflict. YAML dashboards are draft/export
only; when HA omits the mode, its save endpoint remains the final authority. Undo edit is local;
undo save conditionally restores the last baseline and retains newer local changes.

Harc owns `lovelace-catalog/`. `python3 lovelace-catalog/generate.py` publishes declaration-only
`ui-builder.json`, catalog manifest, seed dashboard and domain/device-class templates. The
`Lovelace catalog` workflow publishes to `homeassistant-remotecompose-out`'s
`design-artifacts/lovelace` branch. The preview server serves that catalog alongside `ha-cards`.
Templates bind explicit entity references on placement without altering labels or their source.
Select a section or sections view in Layers and use **Export selected as template** to capture
your own reusable JSON artifact. **Import template** adds it to the placement chooser; each slot
must be bound before placement. The published starter library travels with the harc catalog.

The `/api/cards/resolve` library API can select a nearby prebuilt design and return named
bindings; its shape match can be approximate and some converter-only bindings use library example
values. Dashboard previews use the exact converter render lane instead, so options such as a
gauge's range and arbitrary card config are evaluated by the actual converter. A failed render is
labelled while preserving the config.

The Dashboard/CardEditor catalog entry renders `LovelaceCardPreview` through existing harc card
converters. The host passes card JSON, referenced state snapshot, style and light/dark mode as
preview override knobs. Each reply is correlated with the exact card config. Live state or style
changes trigger a new render; stale replies are discarded. UI Builder plays actual Remote Compose
bytes. Fallbacks remain labelled and config remains editable.

Validation commands:

```
python3 -m unittest discover -s lovelace-catalog -p 'test_*.py'
node --test design-server/test/dashboard-model.test.mjs
./gradlew :ha-client:jvmTest :ha-model:jvmTest :design-server:test :previews:compileDebugKotlin
```

Release order: builder release → preview-server pin/release → harc design-server archive pin,
catalog/preview publication and host image deployment. Verify with the fixture on
`https://ha.coo.ee/studio/dashboard`; do not use a person's existing dashboard for test writes.
