# The dashboard chat: Home Assistant's own agent, from an open dashboard

Terrazzo has no AI provider of its own. Every agent turn runs on the conversation agent the
person already set up in Home Assistant: OpenAI, Google Gemini, Anthropic, Ollama, OpenRouter, or
whatever else, with its own model and key. Terrazzo picks one in the integration's options
(**Dashboard agent**, [HOME_ASSISTANT.md](../HOME_ASSISTANT.md#dashboard-agent)) and adds two
things to it:

- **"Terrazzo dashboards"**, an LLM API with the dashboard tools (`llm_tools.py`, `llm_api.py`).
  The person turns it on in the agent's own options, next to **Assist**.
- **The screen**: which dashboard and view are open, and what the cards on them show.

The apps no longer offer any other way in. The bring-your-own-key designer (`AgentCardDesigner`)
is gone. Dashboard edits no longer use the design server relay or its **Connect an agent** /
**Copy prompt** flow, and the apps don't offer **Ask your agent** for a dashboard unless Home
Assistant's agent is set up. An administrator sees **Set up a chat agent** instead. Card Studio
still uses the relay for now (see [Card Studio, next](#card-studio-next)).

## With a dashboard open

The person opens a dashboard and taps **Ask {agent}** (bottom right). A small chat opens over the
dashboard. In it they can:

| They say | The agent uses | What happens |
| --- | --- | --- |
| "Turn these lights off" | Assist's tools (`HassTurnOff`, …) | Devices switch, as in Home Assistant's own Assist dialog |
| "What's the temperature in here?" | the screen context (or Assist's `GetLiveContext`) | A short answer |
| "Make the thermostat card bigger", "Add a view for the garden" | Terrazzo's dashboard tools | A draft, drawn in place on the dashboard |

One conversation covers both, because they are one agent with two APIs. "These" and "this" mean
what is on screen: each message sends the open view's title and the entities its cards show.

### The turn: `terrazzo/assist`

```
app ──WS terrazzo/assist {text, url_path, view, entities[], conversation_id?, session_id?}──▶ Terrazzo
     Terrazzo ──conversation.async_converse(as the person, agent_id, extra_system_prompt)──▶ agent
        agent ──Assist tools──▶ devices          agent ──Terrazzo tools──▶ edit session draft
app ◀── {speech, conversation_id, session_id, drafted, steps[]} ──
```

- **As the person.** The turn runs with `Context(user_id=…)`, so Assist sees only the entities
  exposed to it, and Terrazzo's tools see only what that person may read. Terrazzo drops any
  `entities` entry the person can't read, or that Home Assistant doesn't have, before naming it to
  the model.
- **What the agent is told** (`assist_chat.screen_prompt`): the home, the dashboard's title and
  `url_path`, the view, and up to 60 on-screen entities with their state now. It also gets the
  household's standing notes (`agent_memory.py`), and a rule for each API: Assist for devices,
  Terrazzo's tools for the dashboard.
- **Where a draft goes** (`assist_chat.for_chat`, through `ChatTurn.prepare`). If the app names
  the edit session it is showing (`session_id`), drafts go there. Otherwise the agent reads the
  dashboard (`get_dashboard`) and calls `submit_draft` with that read's revision and no session.
  Terrazzo fills in the open dashboard's `url_path`, so the draft opens a session on *this*
  dashboard and not on Overview. A draft naming another dashboard, or a new one, is left alone.
- **On the session.** Once a turn has a session, Terrazzo keeps the exchange there too: the
  person's message as a note already marked answered, the reply as the agent's message, and the
  `conversation_id`. The session's own runner (`DashboardAgent`) therefore never answers the same
  message twice. A comment left later on the in-place draft bar continues the same Home Assistant
  conversation while it is still warm (`warm_conversation`).
- **Busy providers** are retried like session turns (`RETRY_DELAYS`), and never after a draft.
- **Steps.** Each Terrazzo tool the agent calls comes back in `steps`, in words
  (`agent_turn.describe_step`). Assist's own tools are not listed; the reply says what changed.
- **Timeout.** A turn can take a minute (several tool calls, plus retries), so the client waits up
  to 180 s for this one command (`ASSIST_TIMEOUT_MILLIS`). Other commands keep 30 s.

`terrazzo/agent/info` (capability `agent` 3) now also returns `chat: true` and `assist`. `assist`
says whether the chosen agent has Assist's tools on (`null` when Terrazzo can't tell). With
`assist: false` the chat offers dashboard changes only, and says how to turn Assist on.

### In the apps

| Host | Where | A draft |
| --- | --- | --- |
| Web, Wasmo, desktop (`app-ui`) | `DashboardChat` over `DashboardDestination`; the view comes from `DashboardContent(onViewShown)` | Drawn in place at once: `DashboardAgentDraft(focusSessionId)` watches the session the reply names, without waiting for its 30 s poll |
| Android (`app`) | `DashboardChat` over `DashboardViewScreen`, which shows every view in one list, so the whole dashboard's entities are named | Opens that session's screen (`AgentSessionsScreen`) with the draft drawn in the dashboard's own cards |

The chat state (`DashboardChatState`) lives as long as the dashboard is open. It holds the lines
said, the `conversation_id`, and the session drafts go to. When a draft is drawn in place
(`onSessionShown`), the chat sends that session's id, so the chat and the draft bar share one
session.

## Why not Home Assistant's own Assist dialog or pipeline?

- `conversation/process` (what HA's frontend Assist dialog calls) takes no extra system prompt, so
  there would be no way to say what is on screen. `async_converse` takes one, and it runs
  in-process with the person's context.
- An **Assist pipeline** (`assist_pipeline/run`, STT → intent → TTS) is the way to add voice. It
  can use the same agent. However, the pipeline's intent stage doesn't take an extra prompt either,
  so a voice turn would lose the screen context unless Terrazzo runs STT and TTS around its own
  `terrazzo/assist`. That is the planned approach: the pipeline's `stt` stage, then
  `terrazzo/assist` with the text, then the pipeline's `tts` stage for the reply.
- Home Assistant's **MCP Server** integration can serve "Terrazzo dashboards" to an outside
  client, and Terrazzo's own MCP server stays for agents signed in to Home Assistant
  ([assistant-access.md](assistant-access.md)). Neither is something the apps set up for the
  person.

## Card Studio, next

Card Studio is the last place where the apps still connect an outside agent: the design server
relay, with a link made by **Connect an agent** in Settings. Moving it to Home Assistant:

1. **One-shot drafts through `ai_task`.** Home Assistant's AI Task integration
   (`ai_task.generate_data`, with a `structure`) runs a prompt on the AI Task entity the person
   chose, with structured output and no conversation. Terrazzo adds `terrazzo/card/draft`: the
   integration seeds the card's UI Builder document from the design server (`/api/studio/seed`,
   as today), asks `ai_task.generate_data` for the new document with the card guide as
   instructions, checks it with the same draft gate (`validateCardDraft`'s server twin), and
   returns it. **Use it** then publishes it exactly as today.
2. **Iterating** ("Not this", a comment) is a second `generate_data` call that carries the last
   draft and the comment. It needs no conversation state.
3. **Then remove the relay from the apps**: `AgentConnection`, `CardAgentSessions`,
   `AgentRelayClient`'s app use, and the Settings entry. After that, `CloudDashboardAgentEdits`
   and the design server's `/api/agent/*` relay can go too. Only `DashboardRelayTest` still uses
   them today.

The plan uses `ai_task` rather than a conversation turn because a card draft is one structured
document, not a chat. A conversation agent would need a submit tool and a session just to return
JSON.

## Tests

| What | Where |
| --- | --- |
| The prompt, where drafts go, the reply | `home-assistant/test/assist_chat_test.py` |
| A real Home Assistant with its Ollama agent (fake model): a chat turn drafts on the open dashboard, the exchange is kept on the session, and nothing answers it twice | `installed-agent-ha.test.mjs` |
| The real panel: ask from the dashboard, the draft drawn in place, **Use it** | `installed-agent-live.test.mjs` (fake model in `ollama-fixture.mjs`) |
| The client call and the chat state and UI | `HaClientAgentEditsTest`, `DashboardChatTest` |

## Not yet

- **Voice** (the Assist pipeline around `terrazzo/assist`, above).
- **Streaming the reply.** `async_converse` answers in one piece; the chat shows a spinner. Home
  Assistant's chat log deltas (`async_get_chat_log`) could stream it later.
- **Card Studio on Home Assistant's agent** (above).
