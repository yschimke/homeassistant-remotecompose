# Dashboard editing across mobile, web and agents

One model for changing a Home Assistant dashboard from the phone, from the web panel, or by an AI
agent. Home Assistant stays the source of truth and every write goes through it with the
permissions of the person who made it.

## Principles

- **The phone does light edits only.** Reordering, resizing, swapping a card's template and
  deleting work on the live dashboard with nothing installed. Anything more is the panel's job,
  and the phone says so rather than squeezing a full editor onto a small screen.
- **Anything further needs the Terrazzo integration** (`custom_components/terrazzo`, installed
  from the Marketplace). It hosts the web panel and the MCP endpoint, and holds agent edit sessions.
- **No new credentials.** Each caller signs in the way it already does (below); nobody hands a
  general-purpose token to an agent. The [dashboard agent](#dashboard-agent) is one of Home
  Assistant's own conversation agents, whose provider key stays in its own integration.
- **An agent never saves.** It submits drafts; a person previews them with real cards and applies
  them with their own session.

## Who signs in how

Home Assistant access tokens are not scoped: any token, long-lived or not, carries its user's full
rights. An administrator's long-lived token given to an agent could change automations, users and
integrations, not just dashboards. So the plan gives no caller a token it doesn't already hold:

| Caller | Signs in with | Can save dashboards |
| --- | --- | --- |
| Phone app | Its own HA OAuth refresh token (`TokenVault`) | If the user is an admin and write mode is on |
| Web panel (`/terrazzo`) | The signed-in user's HA frontend session ([HOME_ASSISTANT.md](../HOME_ASSISTANT.md#authorization-and-lifecycle)) | If the user is an admin |
| Agent | The integration's MCP endpoint, as its pinned user ([assistant-access.md](assistant-access.md)): HA's own OAuth for connector apps, a long-lived token of that user for command-line agents | Never: drafts only |

Home Assistant refuses `lovelace/config/save` and `lovelace/dashboards/create` from
non-administrators, so a dedicated non-admin MCP user can read dashboards and submit drafts but
cannot write one. The person reviewing applies it as themselves. The hosted studio at `ha.coo.ee`
signs in to HA separately; it stays the demo and fixture editor, and the panel inside Home
Assistant is the editor in practice.

## What each surface does

| | Without the integration | With the integration |
| --- | --- | --- |
| Phone | Light edits on the live dashboard; **Open in Home Assistant** for HA's own editor; an **Install from the Marketplace** card | Light edits; **Ask your agent** from the editor's top bar (dashboard or section sessions, copy a prompt); preview, apply or discard agent drafts, or **Adjust** one: it comes back into the editor as an unsaved edit to move and resize by hand and save |
| Web panel | — | Full editor (add cards, sections and views, card settings); **Ask your agent** and the same draft preview |
| Agent | — (no supported path) | MCP: read dashboards and entities, read the card guide, submit drafts to an edit session. Also as a Home Assistant LLM API (**Terrazzo dashboards**, `llm_api.py`): any conversation agent the person has set up, or HA's MCP Server, gets the same editing tools, run as the conversation's user |

## Dashboard agent

With a conversation agent chosen in the integration's options (**Dashboard agent**: any of Home
Assistant's, such as OpenAI, Gemini, Anthropic or Ollama, with **Terrazzo dashboards** on among its
APIs), Home Assistant answers every edit session with it, and the apps lead with it: editing a
dashboard opens a chat with that agent, and editing by hand is the alternative.

| | No dashboard agent | Dashboard agent chosen |
| --- | --- | --- |
| Phone | Light editor; **Ask your agent** (relay or MCP) | **Chat with your agent** first, **Edit by hand** beside it; **New dashboard**; a section heading's long press opens that section's chat |
| Web panel, desktop | Full editor; **Ask your agent** | **Edit** opens the chat; **Chat with your agent** first in the menu, **Edit by hand** beside it; **New dashboard** in the menu and on the dashboard list |
| Sessions | Design server relay when an agent is linked | Home Assistant's own sessions only: no relay, no prompt to copy, no **Adjust** |

How it works (`dashboard_agent.py`, `agent_turn.py`):

- **When.** The integration watches every edit session. A session nobody has answered gets a
  turn as soon as it starts; after that, each note the person leaves (a message, or a comment
  pinned to a card) gets one. A session's `assistant.handled` records the newest note a turn has
  taken, so a note that arrives during a turn runs another; one turn runs at a time per session.
  A session an outside agent already answered gets no first turn, but its later notes are answered.
- **The turn.** One `conversation.async_converse` call to the chosen agent, as the person who
  started the session (their user in the call's context). Its text is what the person said since
  the last turn (the request, on a first turn), and its extra system prompt (`agent_turn.py`)
  names the session to answer, its scope, how to reply, the household's standing notes, and a
  recap of the exchange so far. A turn within four minutes of the last one carries on its
  conversation (`assistant.conversation_id`, `edit_sessions.warm_conversation`): Home Assistant
  keeps an idle one for five, the history is already there, so the recap is left out and the
  prompt stays the same, which lets the provider reuse its prompt cache. Later turns start a new
  conversation, and a session lasts days, so the recap is then the agent's memory.
- **House notes.** `agent_memory.py` keeps one note for the home and one per dashboard in
  `.storage/terrazzo.agent_memory` (`terrazzo/agent/memory` reads them; administrators change one
  with `terrazzo/agent/memory/set`, `home: true` or a `url_path`, empty text to clear). Every turn
  on that dashboard gets them as standing preferences (a pack variant, explicit `bindings` and
  `actions` for helper dashboards, card widths). People write them; the agent can't.
- **Live progress and cancel.** While a turn runs the session carries `active_turn`: `phase`
  (`reading`, `searching_designs`, `drafting`, `retrying`), `status`, `step`, `started_at`,
  `cancellable` and `target_cards` (`{view, section, card, entity}`, `card` null for a whole
  section): the session's section, the cards the notes it answers are about (a note's target
  entity), and the cards showing any entity a tool is called on (`edit_sessions.card_positions`,
  counting wrapped and bound entities). Clients overlay those cards while it runs. It clears when
  the turn ends, and on a restart. `terrazzo/edit/cancel` stops the running turn: the task is
  cancelled, `active_turn` clears, the agent's status reads "Cancelled", and the notes it was
  answering count as answered, so only a new note starts another turn. The agent's own instructions and the **Terrazzo
  dashboards** API (`llm_api.py`, `llm_tools.py`) do the rest: it reads the session with
  `get_edit_session`, looks entities up, and drafts with `submit_draft`; a refused draft goes back
  to it with the reason. Its reply is posted as the agent's message.
- **As whom.** The dashboard tools run as the conversation's user, through the same home object
  the MCP server uses (`mcp_view.HassHome`): only the entities and dashboards that person can see,
  and a draft naming any other entity is refused. Nothing a turn does saves a dashboard.
- **Steps and status.** While a turn runs, `llm_api.TURN` (a context variable around the call)
  links the dashboard tools to the session: before each tool the chat's status line says what it
  is doing, and after it the call is kept on the session as a step (`steps`: the tool, what it
  asked and what it got in a few words, and whether it worked; `agent_turn.describe_step`),
  numbered in the same sequence as the notes and messages, so the person watches the agent work
  between their message and its reply. The newest 100 are kept. An agent that fails (its service
  refused or out of reach) says so in the chat, kept as the session's `assistant_error` until the
  next turn.
- **The chat (`app-ui`, `AgentTranscript.kt`, `AgentLivePanel.kt`).** A turn is the agent's
  steps up to its next message. On Android and desktop it is drawn with
  [agent-render](https://github.com/0xZhangKe/compose-agent-render) (`agentRenderMain`), whose
  steps fold under a summary once the reply comes and whose replies render Markdown; it has no
  Wasm build, so the panel draws the same turns with the app's own rows (`SharedAgentTurn`). The
  message box keeps its labelled field and uses
  [prompt-bar](https://github.com/NadeemIqbal/prompt-bar)'s `@` autocomplete: `@` offers the
  preview's cards (picking one pins the message to it, as its Comment button does) and the home's
  entities (sent as `@Name (entity_id)`). These are kept apart from the rest of the app's screens:
  the agent session flows are expected to move into a shared Livery module.
- **What clients see.** `terrazzo/agent/info` (capability `agent` 2) returns `enabled`,
  `agent_id` and `name` (the agent's name as Home Assistant shows it);
  `HaClient.terrazzoDashboardAgent()` reads it (off for an older integration).
- **Before.** Until entry version 1.2 the integration called an OpenAI-compatible service itself,
  with a provider, model and API key in its own options. The migration removes those options, the
  key with them; choose a conversation agent afresh.

### New dashboards

A session can build a dashboard that doesn't exist yet: `terrazzo/edit/start` with `scope`
`{"kind": "new_dashboard", "title": ..., "icon"?: ...}` (MCP: `start_edit_session` with
`new_dashboard_title`). Home Assistant picks its `url_path` from the title (with the hyphen
Lovelace requires, and not one in use or being built), and the session starts from one empty
sections view. Until it is created, only the person who started it and administrators can see it;
`terrazzo/edit/list` with `new_dashboards: true` lists them. Applying a draft creates the
dashboard with Lovelace's own `lovelace/dashboards/create`, run in-process as the administrator
applying it (its schema and admin check included), then saves the draft into it; if a dashboard
took that path meanwhile, apply is a conflict. Undo deletes it with `lovelace/dashboards/delete`
while it still holds the applied draft.

## Edit sessions

### Cloud agent from the Wasm host

> The agent now joins with a short code and the screen long-polls the design server's relay for
> drafts, presence and messages; comments can be pinned to a card on the draft. See
> [agent-sessions.md](agent-sessions.md). The owner capability is kept in the browser's local
> storage, so a reload resumes the request (the paragraph below predates that).

The shared dashboard host offers a cloud-agent choice alongside the existing direct Home Assistant
MCP path. It creates the regular edit session using the person's signed-in HA connection, then sends
that dashboard's complete configuration and request to the configured HTTPS design server. The
screen names the destination and warns that configurations can contain private data. No HA token is
sent upstream. The one-hour session has separate owner and agent capabilities; the person copies
the agent's temporary connection details. A holder may read the configuration and submit drafts.

The upstream offers `get_dashboard_request` and `submit_dashboard_draft` over scoped MCP, with a
JSON fallback. The browser polls for revisions and relays each new draft through
`terrazzo/edit/submit` as the signed-in person. That command uses the same dashboard visibility and
entity permission checks as MCP draft submission. HA's existing subscription, preview, atomic
apply/conflict checks, and undo remain the review boundary; the upstream never writes to HA. Notes
requested by the person are sent to the upstream for the agent. Discard revokes the upstream
capability before closing the HA session.

The flow needs the Terrazzo HA integration, but no Wasmo backend services. The starting client
keeps the owner capability on the device and also links it to the Home Assistant session
(`terrazzo/edit/cloud`). Home Assistant stores it with the session and returns it only to the
person who started the session, never in what an agent reads. So:

- **Any of that person's clients picks the request up.** The phone, the Wasmo app and the panel
  list the open requests on a dashboard from Home Assistant (`terrazzo/edit/list`), not just the
  ones started on that device, and can continue, comment on, apply or discard any of them.
- **Drafts land with no app open.** The integration long-polls the design server for each linked
  request (`cloud_relay.py`) and submits every new draft as the person who started the session,
  with the same entity checks as a relayed draft, then notifies their phones. The app still
  relays while it is open; both use the relay id `cloud:<revision>`, so a draft never lands twice.
  The design server URL must be HTTPS (plain HTTP only for `localhost`), and Home Assistant calls
  only the relay's two fixed paths on it.
- **The draft shows on the dashboard.** While a session is open, the dashboard draws its newest
  draft in place, with live states, under a bar showing what the agent is doing, **Use it**,
  **Show current**, a message box (pinned to a card by that card's **Comment** button) and
  **Details** for the full session screen. Sharing the prompt or pressing Back from the session
  screen returns to the dashboard. Nothing changes in Home Assistant until **Use it**.

The request on the design server expires an hour after its last activity; Home Assistant forgets
its link once the server no longer has it. The flow shares the selected dashboard config, not a
home-wide entity inventory or live stream. The agent can reuse entity IDs in that config; the HA
relay rejects any submitted entities the person cannot read.

An edit session is the handoff between a person and an agent, held by the integration in Home
Assistant's storage:

1. **Start.** "Ask your agent" in the phone or panel asks the integration for a session: the
   dashboard, its scope (the whole dashboard, or one section), an opening request, the config it
   starts from, who started it, and an expiry. A section session's drafts may change only that
   section. The prompt
   the person copies or shares holds only the MCP endpoint, the session id and the request: no
   token, no dashboard config, no entity list. The agent reads those over its own authenticated
   MCP connection. A connected agent can call `list_edit_sessions` to discover open requests and
   notes on dashboards its pinned user can see, or start a session itself. The list omits stored
   dashboard configurations; `get_edit_session` retrieves the selected dashboard and latest draft.
2. **Draft.** The agent reads the session and the dashboard, uses the card guide (the types harc
   draws, HA's section-grid sizes, the published catalog's preview images), and submits a draft
   with a summary. The integration checks that the draft is a dashboard whose entities exist.
3. **Preview.** The phone and the panel watch the session. A draft appears drawn with the user's
   real cards and live states — the same renderer as the dashboard — so the user sees exactly
   what applying it would show. A message or a comment on a card stores a note the agent reads in
   `await_feedback` (MCP here is request/response: the agent long-polls).
4. **Apply.** An administrator applies the draft from the phone or panel with their own session.
   The integration saves it inside Home Assistant only if the dashboard still holds the config the
   session started from, which it can check and write without another client's save in between —
   unlike a client, which can only read and then write. One undo is kept. YAML dashboards stay
   draft-only.

## Phases

1. **Phone, light editing** — done.
   - The editor (`DashboardEditorScreen`) selects a card to move it (within or between
     sections), resize it on HA's section grid (toolbar or corner drag), swap its template, or
     delete it; sections move up and down. Save is conditional; one undo.
   - **Template** swaps a card showing one entity to another type harc draws for that entity's
     domain (`DashboardDraft.templateChoices` / `withType`), keeping its entity, name, icon, size,
     visibility and actions and dropping keys specific to the old type.
   - Removed from the phone: adding cards, sections and views, card settings and JSON, New
     dashboard, and the copy-paste agent flows (**Edit with an agent**, **Populate with an
     agent**).
   - The editor ends with a card pointing to Home Assistant's own editor and, when
     `get_config`'s `components` lacks `terrazzo`, to the integration's Marketplace page.
2. **Integration, edit sessions** — done.
   - `custom_components/terrazzo/edit_sessions.py` holds the rules with no Home Assistant
     imports: a session keeps its dashboard, request, starting config, drafts (the newest 10) and
     notes, and expires a day after its last change (50 at most). A draft must be a config with a
     `views` list naming only entities the home has. Apply needs the dashboard to still hold the
     starting config; undo needs it to still hold the applied draft.
   - `edits.py` keeps the sessions in `.storage/terrazzo.edit_sessions`, reaches the dashboards
     (only those the caller may see; `require_admin` ones are hidden from other users) and serves
     the WebSocket commands, each as the connection's user: `terrazzo/edit/start` and `get`
     (the session and the prompt to hand an agent), `list`, `subscribe` (the session now and on
     every change), `note`, `discard`, and the administrator-only `apply` and `undo`. Apply and
     undo check and save with no await in between once Lovelace has the dashboard loaded, and
     refuse YAML dashboards.
   - The MCP server adds `list_edit_sessions`, `start_edit_session`, `get_edit_session` (the request, the dashboard as
     it is now, the latest draft and the notes), `get_card_guide` and `submit_draft`. No tool
     saves. `start_edit_session` returns the dashboard it starts from (`dashboard`), and
     `submit_draft` without a `session_id` opens the session itself (`url_path` or
     `new_dashboard_title`, and `request`) and returns its `session_id`, so an edit takes two
     calls, not four. For an existing dashboard it needs the `revision` `get_dashboard` returned
     with the config it changed, and is refused (leaving the session open) when the dashboard has
     changed since: the session's baseline is taken as it opens, so an older read would otherwise
     apply over the edits made in between. A session an agent opens with a tool is marked answered as it is created
     (`edits.async_start(answered=True)`), so the dashboard agent doesn't run a competing first
     turn on it; a note the person leaves later still gets one. In the dashboard agent's own
     turn, a `submit_draft` naming no session or dashboard goes to that turn's session
     (`llm_tools.for_turn`).
   - `find_designs` (MCP server and the built-in assistant): harc's card designs made for an
     entity's device or kind, best fit first, each with the card config that draws it (a
     `custom:remote-compose-card` wrapping a Home Assistant card, with its `design_id`). The
     integration matches the entity's registry facts and `device_class` against the public
     library's suggestion index (`ha.coo.ee/designs/v1/suggestions.json`, cached ten minutes) as
     the card picker does; the request carries nothing about the home. A design that names a
     manufacturer and model as well as an integration still fits a device bridged another way
     (MQTT, Matter, Zigbee2MQTT, a simulator), ranked below one naming the device's own
     integration. `query` (with or without `entity_id`) also searches the designs' titles, ids
     and device fields by word, for helper entities with no device: those configs leave the
     card's entity out when the design is for another domain, to bind through `bindings`.
     `submit_draft` refuses a `custom:remote-compose-card` without a `card` to wrap, with an
     `entities` key (bindings map a design's inputs), or with more than one of `design_id`,
     `design` and `template_url`; its refusals carry a minimal example. A turn the provider
     refuses for want of capacity (503 / 429 / `RESOURCE_EXHAUSTED`) is run again after 2, 5 and
     12 s, unless it already drafted (`agent_turn.transient`). The card guide's
     `remote_compose` rules tell the agent to call it when a person wants a device drawn as the
     real thing, and never to invent a `design_id`.
   - Tests: `home-assistant/test/edit_sessions_test.py` and `mcp_test.py` with plain data and a
     fake home; `installed-edits.test.mjs` in a real Home Assistant: a non-admin's
     `lovelace/config/save` is refused, a session started over the WebSocket is drafted by the MCP
     server's non-admin user (a draft naming a missing entity is refused), the owner's
     subscription sees the draft, the agent can't apply, a dashboard changed meanwhile is not
     overwritten, and apply and undo round-trip.
3. **Ask an agent and draft preview** — done.
   - `HaClient.agentEdits` (`ha-client`, the `AgentEdits` interface) runs the session commands;
     `watch` is the `subscribe` command, unsubscribed when collection stops and failing with its
     connection so the screen re-subscribes after a reconnect. A `conflict` from apply or undo is
     a `DashboardConflictException`. `HaSession.agentEdits` exposes it on the phone (null in demo
     mode).
   - `AgentEditScreen` (`app-ui`, shared by the phone, the desktop app and the panel): a
     dashboard's open and recent sessions and **New session**, or straight into a section's
     session; **Copy prompt** for any session (the phone's share sheet, the panel's clipboard).
     It watches the session and draws the dashboard, then each draft, with `DashboardContent` and
     the host's own card renderer and live states; the person talks to the agent or comments on a
     card; **Use it** (Home Assistant refuses non-administrators), **Stop**, and **Undo** after
     applying.
   - The phone opens it from the editor's integration card when `get_config` lists `terrazzo`, and
     reloads the editor after an apply, and from a dashboard's menu or a section heading's long
     press; the panel and desktop app show **Ask your agent** in a dashboard's top bar.
4. **Agent guidance** — done. `skills/ha-dashboard/SKILL.md` describes the MCP edit session and no
   longer the long-lived-token `lovelace/config/save` path. `DashboardAgentPrompt` keeps only the
   dashboard-and-entities context the placeholder card's coding prompt carries; the card guide
   lives in the integration (`CARD_GUIDE`, `get_card_guide`).
5. **Panel editor** — done. The editor moved to `app-ui` as `DashboardDraftEditor`, with an
   `EditorScope`:
   - `Light` (the phone, through `DashboardEditor` in `app`): the phase 1 edits, ending with the
     integration card.
   - `Full` (the panel and the desktop app, **Edit dashboard** in a dashboard's top bar): also add
     a card (search an entity, then pick from the types that suit it, or a heading, text or
     clock), edit a card's settings, add and delete sections, and add, rename
     (`DashboardDraft.renameView`, keeping the view's path) and delete views.
   - Both draw cards with the host's own renderer, read-only, and save with the same conditional
     write as the signed-in user; Home Assistant refuses non-administrators and YAML dashboards.
   - **Card settings** open on a form of the settings each type uses most (`CardSettings` in
     `ha-client`: names, icons, the entity or the `entities` rows, toggles, numbers such as a
     gauge's range or a graph's hours, the tap action), with the card's Lovelace JSON a tab away
     for anything else. Both edit one copy of the card; a key the form doesn't know is kept, a
     setting back at blank or Home Assistant's default is removed, a tap action set elsewhere
     with more settings (a navigation path) stays while it stays the same action, and a type the
     form doesn't know (custom cards) opens on JSON.
   - Next: creating dashboards from the panel (`DashboardDraft.newDashboard`,
     `HaClient.createDashboard`).

Rendered previews of a draft *for the agent* wait for a server-side renderer (the add-on server's
`.rc` endpoint is a placeholder): until then the agent gets the published catalog images, and the
person sees the real render while reviewing.
