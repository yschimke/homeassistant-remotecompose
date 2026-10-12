# Embedding UI Builder: host-bound RemoteCompose designs

What UI Builder (compose-preview-server) would need in order to be an editor that a host embeds:
the host owns the data, the actions and the runtime, and UI Builder designs only the presentation,
which ships as a RemoteCompose document. Card designs are the first case
([card-design-ui-builder.md](card-design-ui-builder.md)). The same shape covers launcher and Wear
widgets, notifications, tiles, kiosk screens and any server-driven UI a player renders. Written
against 3.121.0.

## The embedded case

Today UI Builder's unit of work is a screen *of an app*: the design owns its state, and the output
is Kotlin that a developer adapts. In the embedded case the arrows run the other way:

```
host ── contract (inputs, actions, examples, target) ──▶ UI Builder ── .rc + manifest ──▶ host
 ▲                                                                                       │
 └──────────── pushes values by name, receives host actions, at run time ◀───────────────┘
```

- The **host** defines a contract: the named values it will push, the actions it can perform, a
  few example datasets, and the runtime it plays on (player version, op profile, sizes, themes).
- **UI Builder** edits a document that only *reads* the inputs and *invokes* the actions. Every
  example dataset previews at every target size.
- The **output** is a RemoteCompose document whose named values and host actions match the
  contract exactly. No generated Kotlin has to be compiled and captured, and no developer adapts
  it. The host can verify it mechanically and publish it as data.

## What 3.121.0 already gives

- **State variables** (`text`/`value`/`selection`; `string`/`bool`/`int`/`float`) and property
  references `{"type": "state", "variable": …}`. Numeric, bool and text properties of `remote-m3`
  accept them, and the canvas shows the initial values.
- **A RemoteCompose canvas** (`rc-player-wasm`). `remote-compose/document` embeds an existing
  document, takes `namedValues`, and binds named values in its inner document.
- **Some structure**:
  - `layout/for-each` over a `data: object`;
  - collapsible and fit layouts, and the design matrix;
  - `remote-compose/custom` for a named custom node.
- **Collaboration and agents**: grants, MCP, comments, branches, forks, `await_decision`, links,
  and `set_implementation`.
- **Create-only seeding over REST**, which [the design server](card-design-server.md) uses.

## What it needs

In priority order. Each item says what it is, why the embedded case needs it, and how this
repository would use it.

### 1. RemoteCompose export that keeps names

The blocker; spike findings 2 and 3 in [card-design-ui-builder.md](card-design-ui-builder.md).

- **`export.rc`**: the document the canvas already plays, as bytes. It should be deterministic,
  with the same bytes for the same revision, so hosts can address blobs by content (our library
  stores `blobs/<sha256>.rc`).
- **Named values survive.** An input `light.s0.name` becomes a named value in the `USER` domain
  (`USER:light.s0.name`), not an anonymous `rememberMutableRemoteString`. The Kotlin export should
  use the named form too (`rememberNamedRemoteString("light.s0.name", …)`), so both exports agree.
- **A manifest beside it**, listing:
  - the named values the document declares, with types and defaults;
  - the host actions it can emit, with payload shapes;
  - the op profile and player version it needs;
  - its byte size.

  A host checks this against its contract without decoding the document. Ours does that decoding
  today with `rc-player-edit`.

Here: generated library templates and UI Builder designs become the same artifact.
`DesignPublisher` takes either one.

### 2. A host contract instead of design-owned state

State variables are design-owned: editable, persisted with the design, and declared one by one. A
host needs to hand over a whole **contract** once and have the design pin to it:

```jsonc
{
  "contract": "ha-card/entities",            // id; designs pin id + revision
  "revision": "7",
  "inputs": {
    "card.title":       {"type": "string", "maxLength": 40},
    "light.s0.name":    {"type": "string"},
    "light.s0.state":   {"type": "string", "form": "formatted"},   // formatState(entity)
    "light.s0.is_on":   {"type": "bool"},
    "sensor.s1.numeric_state": {"type": "float", "form": "raw", "unit": "°C"},
    "light.s0.icon":    {"type": "image"},
    "theme.primary":    {"type": "color", "role": "primary"}
  },
  "lists": {
    "rows": {"item": {"name": "string", "state": "string", "is_on": "bool"}, "max": 12}
  },
  "actions": {
    "toggle":    {"args": {"slot": "string"}},
    "more-info": {"args": {"slot": "string"}}
  },
  "examples": {                               // named datasets, shown as a canvas switcher
    "on":          {"light.s0.is_on": true,  "light.s0.state": "On"},
    "off":         {"light.s0.is_on": false, "light.s0.state": "Off"},
    "unavailable": {"light.s0.state": "Unavailable"},
    "long-name":   {"light.s0.name": "Kitchen ceiling lights over the island"}
  },
  "target": {
    "player": "<remote-compose player version>",
    "profile": "androidx-experimental",
    "sizes": [[360, 120], [180, 180], [216, 76]],
    "themes": ["light", "dark"]
  }
}
```

The editor would treat contract inputs as **read-only sources**: a bindings panel lists them, and
any property can bind to one. Design-local state (an expanded flag, a selected tab) stays as state
variables. Supplied by:
- the create call;
- a contract URL the host serves (re-read when its revision changes);
- an MCP tool (`ui_builder_get_contract`).

A contract revision that renames or removes an input flags every binding to it in the Issues
panel, rather than silently breaking it.

Here: `CardSchema` becomes this contract, one per card schema. Examples come from the catalog,
plus generated `unavailable` and `long-name` sets. The target is `DesignKey` (surface, API level,
theme).

### 3. Host actions

Event properties today run local actions: a switch exports `valueChange(is_on, !is_on)`. A host
needs **host actions** from the contract:

```jsonc
"onCheckedChange": {"type": "hostAction", "action": "toggle", "args": {"slot": "light.s0"},
                    "optimistic": {"override": "light.s0.is_on", "value": {"not": "light.s0.is_on"}}}
```

- **Exported as RemoteCompose `HostAction`s** carrying the action name and its args as metadata.
  This is what our `HaAction` already serialises, and what `TerrazzoWidgetProvider` and the
  playback path resolve.
- **An optional optimistic update**, so the toggle moves before the host round-trip comes back.
  Inputs stay read-only. The update writes a design-local **override** that shadows the input, and
  every read of the input sees the override while it is set. The host's next push of that input
  clears the override, so the host stays authoritative. An override can also time out, so a lost
  push doesn't leave the design showing a value the host never confirmed. The exporter compiles
  this to a local named value plus a conditional read.
- **The canvas logs each invocation** (action and payload) and can apply a scripted response from
  the example dataset. A designer sees what fires.
- **Every interactive component takes an action** (`onClick`, `onLongClick`, `onCheckedChange`,
  `onValueChange`), as `wear-m3` already does with `onClickAction`.

### 4. Bind everything, and compute in the document

Each input in the contract declares whether the host pushes it **raw** (a number, a bool, an enum
value) or already **formatted** (display text the host produces). Whichever it declares, the host
must reproduce the value exactly, the "host-reproducible" rule in
[live-card-updates.md](live-card-updates.md). Example datasets hold the same form, so the preview
matches what runs. A design formats raw inputs itself, so the host never reimplements a design's
formatting. Formatted inputs it shows as they are.

Here, `<slot>.state` stays formatted: it is `formatState(entity)`, with locale, units and
`On`/`Off` capitalisation, and both hosts already push it. `numeric_state`, `is_on` and
`state_int` are raw. A design that wants its own number format reads `numeric_state`; it never
reformats `state`. Moving a binding from formatted to raw is a contract revision: it migrates both
hosts and every design.

- **Every visual property can be bound**:
  - colours: `color: string` can't be bound in `remote-m3` today;
  - images: `asset/image` takes a fixed `assetKey`; it needs a named bitmap input;
  - visibility, alpha and text style too.
- **Expressions** that compile to RemoteCompose expression ops:
  - number formatting (`%.1f`, unit suffix);
  - concatenation;
  - clamp and normalise (a gauge's fill from `numeric_state`, min and max);
  - lookup tables (state → icon or colour role).
- **Conditions and variants**: show, hide or restyle on a predicate (`state == "on"`, is truthy,
  numeric range). These compile to RemoteCompose conditional ops. Here they are what
  `StateEqualsPredicate` / `StateTruthyPredicate` express.

  This is how runtime state stays out of the design key: one design, many states.

### 5. Lists from the host

`for-each` binds to a contract list (`rows` above) with an item schema, and the canvas previews it
at the example counts (0, 1, the maximum). It compiles to a bounded repetition whose slots hide
when unused, or to RemoteCompose collections where the profile has them. Without this, every
variable-length card (entities, glance, to-do, forecast) needs one design per count.

### 6. Validate against the target and the contract

`ui_builder_check_design` covers the schema, the catalog and accessibility. The embedded case
adds:
- **ops** within the target profile and player version;
- **bindings** within the contract's inputs; required inputs used; action args well typed;
- **every example** renders at every target size and theme with no clipping, and the document is
  **the same for every value** (values arrive by name, never by re-encode);
- a **size budget** for the document's bytes.

These rows should feed the existing check and `await_decision`, so an approval means "valid for
this host".

### 7. Theme roles from the host

Designs should reference **roles** (`primary`, `onSurface`, `surfaceContainer`) and typography
roles, not hex values. The host supplies them as colour inputs, so one design follows the user's
dynamic colour and dark mode. The contract's `themes` drive the matrix. This also fixes the
`remote-m3` problem that light and dark look the same.

### 8. Host-supplied catalogs

`remote-m3` is Wear-specific, and a host has its own primitives (here: an entity row, a state
chip, an icon with accent, an arc dial, a sparkline). A host should be able to publish a
**RemoteCompose catalog**, without the compose-ui-builder source: components as named RemoteCompose
fragments (built on `remote-compose/custom`) with declared bindable properties and slots. Plus a
generic phone `remote-material3` catalog beside the Wear one.

### 9. An embedding surface

- **Embed mode**:
  - the editor in an iframe or webview with host chrome hidden;
  - a `postMessage` API to load a contract and dataset, switch examples, receive action
    invocations and hear "saved" and "approved";
  - a return URL.
- **Scoped links**: a capability URL that edits *one* design, issued by the host for its user.
  The operator token unlocks everything, and GitHub sign-in assumes developers. This is what
  per-user forks from the app need.
- **Webhooks** on revision, approval and comment, so the host publishes approved revisions
  without polling. A comment webhook (`--ui-builder-comment-webhook`) exists today.
- **Live data in the canvas, optionally**: the host streams real values into an open editor
  (`namedValues` over a socket), so a user designs against their own home. It's opt-in and stays
  in the session; it is never saved into the design.

### 10. Agent tools for the same model

MCP tools that speak contract and dataset:
- `ui_builder_get_contract`;
- `ui_builder_render_design_matrix` taking `examples`;
- `ui_builder_simulate_action` (fire an action, see the optimistic state);
- the contract rows of `check_design`.

An agent working in a user's design session then has the same picture as the designer:
"make this card work at 180×180 with the long-name example" is checkable.

## Order

| # | Enhancement | Unblocks |
|---|---|---|
| 1 | `export.rc` with named values + manifest | any design reaching a host at all |
| 2 | Host contract: read-only inputs, examples, target | seeding without hand-made state, contract checks |
| 3 | Host actions with optimistic updates | interactive cards and widgets |
| 4 | Bind colours (needed for the first slice), then images and visibility; expressions; predicates | real cards (formatting, states, icons) |
| 6 | Target and contract validation | publishing without a person checking bytes |
| 9 | Scoped links, webhooks, embed mode | the app entry point, per-user forks |
| 5 | Host lists | variable-length cards |
| 7 | Theme roles | dynamic colour, light and dark |
| 8 | Host catalogs | phone-card primitives |
| 10 | Agent tools | agent sessions on a user's design |

The first three, plus colour binding from item 4, are enough for a vertical slice: the design
server seeds from a contract, a person binds a tile's text, colour and toggle, and the exported
`.rc` publishes as a library variant the
app plays live. Everything after that widens what can be designed rather than whether it ships.
