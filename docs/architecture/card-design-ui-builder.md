# Designing cards in UI Builder

How a card goes from its schema (or a Lovelace JSON) to a UI Builder design of its responsive
content, and what is missing between that design and a template the
[card design server](card-design-server.md) publishes. Written against compose-preview-server
3.121.0, the release the design server bundles.

## What exists

- **Card schema** (`design-server`, `CardSchema`). A card's data contract: its slots
  (`<domain>.s<N>`, by position) and every named value a client pushes for it — per slot `name`,
  `state`, `is_on`, `state_int`, `status`, `numeric_state`, plus card-level values such as
  `card.title` — each with a type and an example value. It comes from a library design (the
  template's bindings decoded with `rc-player-edit`, with its catalog example's values) or from
  any card's Lovelace config and states (`CardDesignSlots.slotBindings`, the bindings the app
  pushes). Served at `/api/designs/<id>/schema` and `POST /api/schema`; `/schema` is a page for
  pasting a card's JSON.
- **UI Builder, bundled.** `UI_BUILDER_BINARY` makes the design server run
  `compose-preview-server ui --no-project` as a child process with a fixed operator token (the
  container image does this by default); `UI_BUILDER_URL` + `UI_BUILDER_TOKEN` point it at one
  that runs elsewhere. Browsing stays public. Editing needs the edit link the server prints at
  startup, which sets a cookie; UI Builder itself is gated by the same token.
- **Seeding** (`UiBuilderSeed`). "Design in UI Builder", on a design's page or the schema page,
  creates a `remote-m3` design (`PUT /api/ui-builder/v1/designs/<id>`, create-only) and opens it
  in the editor:
  - every binding becomes a **state variable** with the same name and the example as its initial
    value (text → `text`/`string`; `is_on` → `value`/`bool`; ints and floats → `value`/`int`,
    `float`);
  - a starting layout already reads them: a `remote-card` holding a `collapsible-column` with the
    title, then one `collapsible-row` per slot (name and state texts, and a
    `remote-switch-button` whose `checked` is the slot's `is_on` for toggleable domains).

  The seeded design passes UI Builder's schema, catalog and accessibility checks
  (`UiBuilderTest`, and a CI run against the bundled image).

## What the spike found

Each of these was checked against a running UI Builder, not inferred from documentation.

1. **Binding works in the document.** A property can hold `{"type": "state", "variable": "…"}`,
   and `remote-m3` accepts it for `remote-text.text`, `checked`, `progress`, `enabled` and other
   `…|object` properties. The canvas shows the initial values, so a designer sees the card with
   the example's values.
2. **The exporter loses the names.** `export.compose` writes
   `val light_s0_name = rememberMutableRemoteString("Kitchen")`: an anonymous mutable value. A
   card template needs a **named** one (`USER:light.s0.name`, what
   `rememberNamedRemoteString` / `LocalTemplateBindings` capture), or the client can't push
   values into it. This is the main gap: the binding schema is exactly the state variables'
   names, so the exporter only has to keep them.
3. **No RemoteCompose export.** Formats are `compose`, `svg` and `png`; `bundle` is refused for
   `remote-m3`. The canvas already plays the design as a RemoteCompose document (`rc-player-wasm`),
   so an `.rc` export of that document — with named state — would skip compiling generated
   Kotlin entirely.
4. **`remote-m3` is Wear Material 3.** It draws Wear components (a dark rounded card, a large
   switch pill) whatever the frame's theme, so light and dark render the same. That fits Wear
   widgets; phone dashboard cards need a catalog of this repository's card primitives (entity
   row, state chip, icon with accent, toggle, sparkline, arc dial…) with bindable properties.
   The opt-in `ui-builder-catalog` module wraps whole cards (tile, entities, glance) for the
   typed adapter API; it is the starting point, but it binds scalars, not named state.
5. **Actions are local.** A switch exports `onCheckedChange = valueChange(is_on, !is_on)`. A card
   must run its host's action instead (toggle the entity, more-info): a design needs event
   bindings that name a template action (`HaAction` by slot), which the design server's playback
   already resolves for templates.
6. **Adaptive building blocks exist, with caveats.** `collapsible-column` / `collapsible-row`,
   `fit-box`, `flow-row`, `for-each` (rows over a data list, for variable-length cards) and, for
   Wear, `widget-container-adaptive` (headline / supporting / action slots). The design matrix
   (`ui_builder_render_design_matrix`) renders custom card sizes side by side. At 160 dp the
   collapsible row truncated its text rather than dropping the switch, so the collapse order needs
   checking per component. The exporter flags collapsible layouts as outside the Glance Wear
   widget profile; the mobile app's profile has them, but nothing validates a design against the
   target profile yet.
7. **Creating designs is easy.** A whole document, state variables included, goes in one create
   call with the operator token; no operation log is needed to seed.

## What a full pipeline needs

From a card's schema to a published, adaptive template, in order:

| # | Step | Where | Status |
|---|---|---|---|
| 1 | Schema from a design or a card's JSON | design server | done |
| 2 | Seed a design whose state variables are the bindings | design server | done (`remote-m3`) |
| 3 | A phone card catalog: HA primitives with bindable properties, themed by the card's theme roles | `ui-builder-catalog` (here), published | to do |
| 4 | Export keeps state names: named RemoteCompose values (`USER:<binding>`) | compose-preview-server | to request |
| 5 | Export a RemoteCompose document (`.rc`), or compile the exported Kotlin in `:card-designs` and capture it | compose-preview-server / `:card-designs` | to request / to build |
| 6 | Event bindings that name template actions (toggle, more-info, per slot) | catalog + exporter | to request |
| 7 | Validate: op set of the target profile, bindings ⊆ schema, the same document for every value (snapshot invariance) | `:card-designs` validator | partly (op set) |
| 8 | Publish approved revisions into the library as a variant (`DesignKey.variant = "ui-builder"`), linked to the design (`ui_builder_set_links`) and gated on a person's approval (`ui_builder_await_decision`) | `:card-designs`, design server | to build |

**Generation properties stay structural.** A design is for one shape, as today: the seed is per
schema, and a design's key keeps its card type and shape. Runtime state (on/off, unavailable)
belongs in bindings and state predicates (`StateEqualsPredicate`, `StateTruthyPredicate`), never
in another design.

**Responsive, not per size.** One design per shape should adapt to the card's size through
collapsible and fit layouts and `for-each`, checked on the design matrix at the sizes a card takes
(dashboard columns, launcher cells, Wear small/large). Branching designs by width is the thing
adaptive layout avoids.

The UI Builder side of steps 4–7, written as general enhancements for any host that embeds UI
Builder to produce RemoteCompose, is in [ui-builder-embedding.md](ui-builder-embedding.md).

## Running it

```sh
./gradlew :card-designs:generateCardDesigns :design-server:installDist
UI_BUILDER_BINARY=<compose-preview-server>/bin/compose-preview-server \
  design-server/build/install/design-server/bin/design-server card-designs/build/card-designs
```

Open the printed edit link, then a design's page (or `/schema`) and "Design in UI Builder". The
container image bundles UI Builder at port 8724; see `design-server/Dockerfile`.
