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
between read and write. Export/reload/reapply resolves a conflict. Loading another dashboard first detaches the save target;
a failed load retains the old draft for export, and overlapping loads cannot replace a newer
selection. The host correlates editor acknowledgements with each open request before enabling saves. YAML dashboards are draft/export
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

## Edit dashboards on Android

The dashboard overflow menu has **Edit dashboard** and **New dashboard**.

**New dashboard** asks for a title, registers a storage-mode dashboard
(`lovelace/dashboards/create`, with a `url_path` derived from the title; HA requires a hyphen in
it) and saves a first config of one sections view holding one empty section, since a dashboard HA
has just created has no config to read. The new dashboard joins the user's dashboard selection,
opens, and opens in the editor. It needs write mode and an administrator; HA's refusal is shown.

**Edit dashboard** loads the dashboard's stored Lovelace config as a draft
(`DashboardEditorScreen`). Cards are the same live previews the dashboard draws, fed by the live
state feed (or demo polling), so they keep updating while you edit. Each section is laid out on HA's
12-column section grid (`SectionCardGrid`): a card's size in the editor is its `grid_options`, the
size it has wherever HA shows the dashboard. The dashboard view itself flows cards by harc's own
design sizes (`SectionCardFlow`), so it can place cards differently.

- Tap a card to select it; taps never reach the card's own actions in the editor. The toolbar moves
  it earlier or later or to another section, steps its width through 3/6/9/12 columns, sets fixed
  rows or `auto` height, edits or deletes it. Dragging the corner handle resizes in whole columns
  and rows, and the cards after it re-flow as it grows or shrinks. Resizing writes `grid_options`
  (and drops a legacy `layout_options`), clamped to the card type's limits.
- **Add card** picks an entity from the home, offers card types that suit its domain, and takes a
  name; heading, markdown and clock cards need no entity. The card's JSON is editable directly.
- **Add section** appends a section titled by a heading card; sections move up and down or are
  deleted. The overflow menu adds or deletes views.
- Every edit is local and undoable. **Save** is conditional (`DashboardEditSession` over
  `compareAndReplaceDashboardConfig`): if HA's copy changed since the draft was loaded the save is
  refused and the draft kept. A saved change can be undone once. Read-only mode and demo sessions
  edit but don't save.

Edits go through `DashboardDraft` (`ha-client`), pure JSON operations that keep unknown view,
section and card fields, custom cards included.

### Agent handoff

**Populate with an agent** (the sparkle in the editor) copies or shares a prompt carrying the draft,
the home's entities and states, the request, HA's card sizes (`DashboardAgentPrompt.SIZING`) and
how to look at harc's card designs (`DashboardAgentPrompt.DESIGN_TOOLS`): the hosted Compose
Preview catalog (`homeassistant-remotecompose` on `https://preview.coo.ee/mcp`), where
`catalog_list_previews` with `card-<type>` finds a card's designs and `catalog_render_preview` /
`catalog_render_matrix` with the `device` override shows them at other widths. The agent's answer
replaces the draft, unsaved, so the user arranges and resizes it before saving. The prompt includes
no credentials. `skills/ha-dashboard/SKILL.md` carries the same guidance for agents that load
skills.

## Plan cards on Android

The Android dashboard has **Add placeholder card** above its HA cards. Give a planned card a title
and description; optionally name HA entity IDs and the Remote Compose component/binding map you
want (for example, a soil-moisture gauge with the sensor state bound to its value). You can edit
or remove each brief. Briefs survive app restarts on this phone and are scoped by HA server and
dashboard. They are local planning items, so they are available in read-only mode and are never
written into the HA dashboard or sent to the renderer as custom Lovelace cards.

**Copy implementation prompt** reads the current dashboard and entity snapshot using the existing
HA session. The clipboard contains the brief, dashboard JSON, and entity names/states, so review it
before sharing. The prompt asks a coding agent to implement fetching/subscriptions in the existing
host/client layer, reuse Remote Compose components, define live named bindings (or a dataSignature
re-encode), and test updates, unavailable values and reconnects. Unknown entity IDs are identified
as unresolved rather than invented. The prompt does not include the session's credentials.

This is a handoff to a coding agent, not an in-app code executor. Install the agent's implementation
and place its supported card through the usual dashboard workflow; then remove the brief manually.
The web visual editor, the mobile **Edit with an agent** JSON-import flow and the mobile dashboard
editor above remain separate.
