# Dashboard editing across mobile, web and agents

One model for changing a Home Assistant dashboard from the phone, from the web panel, or by an AI
agent. Home Assistant stays the source of truth and every write goes through it with the
permissions of the person who made it.

## Principles

- **The phone does light edits only.** Reordering, resizing, swapping a card's template and
  deleting work on the live dashboard with nothing installed. Anything more is the panel's job,
  and the phone says so rather than squeezing a full editor onto a small screen.
- **Anything further needs the Terrazzo integration** (`custom_components/terrazzo`, installed
  from HACS). It hosts the web panel and the MCP endpoint, and holds agent edit sessions.
- **No new credentials.** Each caller signs in the way it already does (below); nobody hands a
  general-purpose token to an agent.
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
| Phone | Light edits on the live dashboard; **Open in Home Assistant** for HA's own editor; an **Install with HACS** card | Light edits; **Ask an agent** (start an edit session, share its prompt); preview, apply or discard agent drafts |
| Web panel | — | Full editor (add cards, sections and views, card settings as JSON); **Ask an agent** and the same draft preview |
| Agent | — (no supported path) | MCP: read dashboards and entities, read the card guide, submit drafts to an edit session |

## Edit sessions

An edit session is the handoff between a person and an agent, held by the integration in Home
Assistant's storage:

1. **Start.** "Ask an agent" in the phone or panel asks the integration for a session: the
   dashboard, the request, the config it starts from, who started it, and an expiry. The prompt
   the person copies or shares holds only the MCP endpoint, the session id and the request: no
   token, no dashboard config, no entity list. The agent reads those over its own authenticated
   MCP connection. A connected agent can also start a session itself.
2. **Draft.** The agent reads the session and the dashboard, uses the card guide (the types harc
   draws, HA's section-grid sizes, the published catalog's preview images), and submits a draft
   with a summary. The integration checks that the draft is a dashboard whose entities exist.
3. **Preview.** The phone and the panel watch the session. A draft appears drawn with the user's
   real cards and live states — the same renderer as the dashboard — so the user sees exactly
   what applying it would show. "Request changes" stores a note the agent reads on its next call
   (MCP here is request/response: the agent is not pushed to).
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
     `get_config`'s `components` lacks `terrazzo`, to the integration's HACS page.
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
   - The MCP server adds `start_edit_session`, `get_edit_session` (the request, the dashboard as
     it is now, the latest draft and the notes), `get_card_guide` and `submit_draft`. No tool
     saves.
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
   - `AgentEditScreen` (`app-ui`, shared by the phone, the desktop app and the panel): write a
     request and start a session, share its prompt (the phone's share sheet, the panel's
     clipboard), or pick an open session back up. It watches the session and draws each draft
     with `DashboardContent` and the host's own card renderer and live states; **Apply** (Home
     Assistant refuses non-administrators), **Request changes** (a note), **Discard**, and **Undo**
     after applying. Older drafts stay selectable.
   - The phone opens it from the editor's integration card when `get_config` lists `terrazzo`, and
     reloads the editor after an apply; the panel and desktop app show **Ask an agent** in a
     dashboard's top bar.
4. **Agent guidance** — done. `skills/ha-dashboard/SKILL.md` describes the MCP edit session and no
   longer the long-lived-token `lovelace/config/save` path. `DashboardAgentPrompt` keeps only the
   dashboard-and-entities context the placeholder card's coding prompt carries; the card guide
   lives in the integration (`CARD_GUIDE`, `get_card_guide`).
5. **Panel editor** — done. The editor moved to `app-ui` as `DashboardDraftEditor`, with an
   `EditorScope`:
   - `Light` (the phone, through `DashboardEditor` in `app`): the phase 1 edits, ending with the
     integration card.
   - `Full` (the panel and the desktop app, **Edit** in a dashboard's top bar): also add a card
     (search an entity, then pick from the types that suit it, or a heading, text or clock),
     edit a card's settings as its Lovelace JSON, add and delete sections, and add, rename
     (`DashboardDraft.renameView`, keeping the view's path) and delete views.
   - Both draw cards with the host's own renderer, read-only, and save with the same conditional
     write as the signed-in user; Home Assistant refuses non-administrators and YAML dashboards.
   - Next: creating dashboards from the panel (`DashboardDraft.newDashboard`,
     `HaClient.createDashboard`), and forms for common card settings in place of JSON.

Rendered previews of a draft *for the agent* wait for a server-side renderer (the add-on server's
`.rc` endpoint is a placeholder): until then the agent gets the published catalog images, and the
person sees the real render while reviewing.
