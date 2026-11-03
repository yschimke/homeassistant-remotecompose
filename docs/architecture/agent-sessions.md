# Live agent sessions

How a person and an AI agent edit a card or a dashboard together. The person connects their agent
once; after that every request goes straight to it, with nothing to copy. The person sees what the
agent is doing, each draft appears by itself drawn with their own cards and live states, and they
talk until the person accepts or discards. It works the same in the Android app, the desktop app
and the browser (the Wasm app and its Wasmo install), whatever sits in front of Home Assistant.

## Why a relay

| Constraint | Consequence |
| --- | --- |
| A cloud agent usually can't reach Home Assistant: local-only installs, reverse proxies, Nabu Casa, ingress | Nothing may depend on an inbound connection to HA |
| HA tokens are not scoped | No agent ever gets one; it submits drafts, a person applies them |
| The phone is often in the background, and a browser tab can reload | The person's side must pick a session back up |
| Proxies cut long requests at about 60–100 s and buffer server-sent events | Long-polls are held for at most 25 s; no SSE, no WebSockets to the relay |
| MCP is request/response | An agent waits for the person with a long-polling tool (`await_feedback`) |

So the design server (`ha.coo.ee`) is a rendezvous point: the agent and the person's app both
connect *out* to it. Only the person's app talks to Home Assistant, over the connection it
already has.

```
Agent (Claude, Codex, …) ──MCP──▶ design server relay ◀──HTTPS long-poll── person's app ──WS──▶ Home Assistant
                                  drafts, code, presence,                  (Android, desktop,      checks and
                                  conversation, decision                    Wasm, Wasmo)            applies drafts
```

## The flow

**Ask your agent** is the one flow, for a card (its long-press sheet, or below it on desktop) and
for a dashboard (its menu), on every host. Under **Advanced**, a dashboard can use an agent signed
in to Home Assistant instead, and a card can be designed by hand in UI Builder.

1. **Connect, once.** Settings → **Connect an agent** (also shown at the start of **Ask your agent** until an agent is connected) shows a
   code such as `K7QM-4MD2`. **Copy invite for the agent** copies one message with the code and
   the MCP address. The agent calls `connect` with it and keeps the **link** it gets back. The
   other direction works too: the agent calls `request_access`, shows the person a code, and the
   person enters it under **Have a code from your agent?**. Settings then say "Claude is connected
   and listening", or when it last listened. **Disconnect** ends the link.
2. **Ask.** In **Ask your agent**, the person writes what should change and sends it. The app
   sends the card's preview contract or the dashboard's configuration to the design server with
   its link, and the request goes straight to the agent, waiting in `await_request`: no code,
   nothing to copy. Without a connected agent the request gets its own code instead, as in step 1.
3. **Presence.** The panel shows "Claude · Drafting a compact layout" from the agent's tool calls
   and `set_status`, "was here 3 min ago" when it's quiet, or "Waiting for an agent to join".
4. **Drafts arrive.** The app long-polls the relay, so a submitted draft shows up within moments,
   with no **Check for draft**. A dashboard draft is relayed into Home Assistant's edit session
   (`terrazzo/edit/submit`) as the signed-in person and drawn with their real cards; a card draft
   is loaded with its images, checked against the card's contract, and drawn in place of the card
   with its live values, built exactly as **Use it** would publish it
   (`POST /api/studio/agent-session/preview`); **Use it** waits for that drawing, and a draft that
   can't be built says why instead.
5. **Talk.** The person types in the panel. On a dashboard draft each card has a **Comment**
   button that pins the next message to that card (its title or entity, and where it sits). The
   agent, waiting in `await_feedback`, gets the message at once, and can `send_message` back.
6. **Decide.** **Use it** applies the dashboard draft, or puts the card design on the card (the
   design server opens it as the person's own design and publishes it privately, compiling it to
   a template itself with `UiBuilderDesignCompiler`, which draws what the server seeds a card
   with: collapsible rows, a switch on the slot's `is_on`; no browser), and tells the agent
   `accepted`; **Undo** puts a dashboard back. A message asks for changes (on a card, **Not this** asks for another try in
   one tap, and the session stays open); **Stop**
   ends the session for both sides.

## Lifetimes and storage

- A link lasts 30 days after either side last used it, or until the person disconnects it. It
  holds no Home Assistant credential and grants nothing by itself: each request it delivers
  carries its own session capability for that one card or dashboard.
- A code (setup or per request) is single use and lasts 15 minutes; **New code** replaces it. An
  agent's `request_access` code lasts 10 minutes.
- A session ends an hour after its last activity (either side), never more than a day after it
  started, or on revocation. A design server restart or deploy does not end it: sessions, unused
  codes, presence and the conversation are saved to `AGENT_SESSIONS_DIR` (`/data/agent-sessions`
  on the server's volume) a second after each change, atomically and readable by the server
  alone, and restored on start (`AgentSessionFile`). Without that directory they last until a
  restart.
- The link and the sessions' owner capabilities are kept on this device: the browser's
  `localStorage`, the Android app's no-backup files, `~/.terrazzo/agent-grants` on desktop. A
  reload or restart resumes them. They are never written to Wasmo's synced settings: a capability
  is a secret for this device.
- Wrong codes share one budget: after 30 failures in a minute every redemption waits, which keeps
  eight-character codes (31 symbols, no `0/O/1/I/L`) unguessable in practice.

## Interfaces

**Agent (MCP)** — `POST /api/agent/mcp`, stateless JSON-RPC, no header needed; every tool after
joining takes the `session` argument (a `Bearer` header works too). Browser origins are refused.

| Tool | Does |
| --- | --- |
| `connect(code, agentName?)` | Redeem a code: a setup code returns a lasting `link`, a request's code its `session` (`join_session` is the same) |
| `await_request(link, since)` | Waits up to 25 s for the person's requests after `since`; each carries its `session` |
| `request_access(agentName?)`, `poll_access(requestToken)` | Ask first; the person enters the code (in Settings for a link); `poll_access` waits up to 25 s |
| `get_session(session)` | Request, card document or dashboard, `revision`, conversation, `cursor` |
| `set_status(session, status)` | What the person sees the agent doing |
| `send_message(session, text)` | A message in the person's panel |
| `submit_draft(session, revision, document \| dashboard, summary?)` | Validated by the per-kind gate (card contract, dashboard shape) |
| `put_card_asset(session, key, imageBase64)` | Card images, as before |
| `await_feedback(session, since)` | Waits up to 25 s for comments or a decision after `since` |

`POST /api/agent/join {code}` trades a code for the session for agents without MCP; they then use
the existing per-kind JSON routes (`/api/studio/agent-session`, `/api/studio/dashboard-session`).
The per-kind MCP endpoints (`/api/studio/agent-mcp`, `/api/studio/dashboard-mcp`) keep working
with a pasted capability, and also count as presence.

**Person (HTTPS, CORS `*`, owner capability as bearer)**

| Route | Does |
| --- | --- |
| `POST /api/agent/links` | Start the setup: the link's owner capability and its code |
| `GET /api/agent/link?since=<version>` | The link: connected, agent name, listening, unused code |
| `POST /api/agent/link/code`, `POST /api/agent/link/claim {code}`, `DELETE /api/agent/link` | New code, enter the agent's code, disconnect |
| `GET /api/agent/session?since=<version>` | State now, or held until it changes past `version` (25 s at most) |
| `POST /api/agent/session/join-code` | A fresh code |
| `POST /api/agent/claim {code}` | Let in the agent that asked first |
| `POST /api/agent/session/comment {text, target?}` | A message to the agent; on a dashboard also a session note |
| `POST /api/agent/session/decision {decision}` | `accepted` or `rejected` |
| `POST /api/studio/agent-session/preview` (a `DesignKey`) | The card draft built as a template for that key, or 422 and why |
| `DELETE /api/agent/session` | Revoke |

Session creation is `POST /api/studio/agent-sessions` and `POST /api/studio/dashboard-sessions`;
with `agentLink` in the body the request is delivered to the linked agent (`linked: true`),
otherwise the answer carries its `joinCode`. The server keeps the older per-kind routes
(`/api/studio/agent-mcp`, `/api/studio/dashboard-mcp`, a pasted capability) for apps already
installed; current apps no longer use them.

| Piece | Where |
| --- | --- |
| Relay: codes, presence, conversation, long-polls, sliding expiry | `design-server/.../AgentRelay.kt` |
| Saved across restarts | `AgentSessionFile.kt` |
| Direct path: `await_feedback`, `send_message`, `set_status` | `custom_components/terrazzo/edit_sessions.py`, `edits.py`, `mcp.py` |
| MCP and HTTP routes | `AgentRelayMcp.kt`, `AgentRelayRoutes.kt` |
| Drafts and their validation (unchanged) | `AgentDesignSessions.kt`, `DashboardAgentSessions.kt` |
| Client | `ha-client/.../agent/AgentRelayClient.kt`, `CloudDashboardAgentEdits` |
| Panel (both screens, every host) | `app-ui/.../shared/AgentLivePanel.kt`; `CardStudioScreen`, `AgentEditScreen` |
| Tests | `AgentRelayTest`, `DashboardRelayTest` (code → join → draft → HA → comment → accept), `AgentLivePanelTest` |

## Agents signed in to Home Assistant directly

An agent that can reach Home Assistant (on the LAN, or through a public URL) uses the integration's
own MCP server instead of the relay ([assistant-access.md](assistant-access.md)). Adding that
MCP server is its setup; after it, `await_request(since)` listens for sessions the person starts,
and while an agent is listening the app says "Your agent is listening and picks this up now" in
place of the prompt to hand over. It gets the same loop: `set_status` and `send_message` show in the person's panel, `submit_draft` appears at once
over the edit session's subscription, and `await_feedback(session_id, since)` waits up to 25 s
for the person's notes, which can name a card (`target`), or for the session to be applied or
discarded. Notes and messages share one sequence (`seq`), so `since` says which notes the agent
has read; presence ages by Home Assistant's clock (`server_time` in every reply). The screen shows
the prompt to hand the agent until the agent first calls a tool, then the same live panel.
`installed-agent-live.test.mjs` runs this end to end in a real Home Assistant with the real
panel: ask from the menu, draft, a comment pinned to a card, apply.

## The agent sees what the person sees

A moment after a dashboard draft appears on the person's screen, the app captures it as drawn
(their own cards, card style and live states, at most 1080 px wide) and shares it as a PNG: with
the relay for a cloud agent (`get_render`), with the integration for an agent signed in to Home
Assistant (`terrazzo/edit/render`, `get_draft_render`). Both return MCP image content. Renders are
kept in memory only, the newest per session; the app shares again whenever it draws a draft.
A card draft is drawn by the app from the template the design server builds for it (the same
build **Use it** publishes), so the person decides on what they will get.

## When the person isn't watching

When an agent signed in to Home Assistant submits a draft and nobody has the session open, the
integration tells the person who started it on their Terrazzo phones, through `mobile_app`'s own
notify (held and woken through the push relay when the app is closed;
[notifications.md](notifications.md)): "Your agent has a draft", with its summary, one notice per
session. A cloud agent's drafts reach Home Assistant through the person's app, so they arrive
when the app next shows the session.

## Not yet
- **Notices for cloud drafts while the app is closed.** The relay has no way to reach the phone;
  the integration holding the person's relay link would let it relay and announce drafts itself.
- **One session for a card and its dashboard.** Card and dashboard sessions share the relay and
  the MCP tools but are still started separately, and accepted separately.
- **Card sessions in the browser end-to-end test.** The cloud path is covered against the real
  relay routes and a fake Home Assistant (`DashboardRelayTest`); a browser run needs a design
  server with UI Builder beside the Home Assistant container.
