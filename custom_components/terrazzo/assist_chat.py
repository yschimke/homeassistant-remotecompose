"""The dashboard chat: Home Assistant's own conversation agent, asked from a dashboard the person
has open (`terrazzo/assist`). `dashboard_agent.py` runs the turn in Home Assistant.

One chat does both things a person asks of their home while looking at it. Assist's tools (the
agent's "Assist" API, for the entities exposed to it) switch and read devices; Terrazzo's dashboard
tools (`llm_tools.py`) change the dashboard. What this adds, as the turn's extra system prompt, is
what is on the person's screen: which dashboard and view, and the entities its cards show with
their states now, so "turn these off" or "make this card bigger" mean what the person sees.

A draft goes to an edit session on the open dashboard: the one already open on it when the app
names it, else one the agent's `submit_draft` opens. The app then draws that draft in place, and
later turns carry on in the same session and the same conversation.

Free of Home Assistant imports, like `agent_turn.py`, so it is tested on its own
(home-assistant/test/assist_chat_test.py).
"""

ASSIST_API = "assist"
DASHBOARDS_API = "terrazzo_dashboards"

MAX_TEXT = 2000
# Entities on screen the prompt names: enough for a full view, bounded for a huge one.
MAX_ON_SCREEN = 60
MAX_STATE_CHARS = 60
# The dashboard tool steps a reply carries, newest kept.
MAX_STEPS = 30


def clean_text(text):
    """[text] as the person's message: trimmed and at most `MAX_TEXT`; raises for nothing said."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("Say something to the agent")
    return text.strip()[:MAX_TEXT]


def clean_entities(entity_ids, can_read):
    """The entity ids the app says are on screen, as the prompt may name them: well-formed, ones the
    person may read ([can_read]), each once, in the app's order, at most `MAX_ON_SCREEN`."""
    seen = []
    for entity_id in entity_ids or []:
        if not isinstance(entity_id, str) or "." not in entity_id or entity_id in seen:
            continue
        if not can_read(entity_id):
            continue
        seen.append(entity_id)
        if len(seen) >= MAX_ON_SCREEN:
            break
    return seen


def apis_on(apis):
    """Which of the two APIs a chosen agent's options turn on, from its `llm_hass_api` (a list of
    ids, one id, or absent): `{"assist": bool, "dashboards": bool}`, or None when Terrazzo can't
    tell (an agent that keeps its APIs elsewhere)."""
    if apis is None:
        return None
    if isinstance(apis, str):
        apis = [apis]
    if not isinstance(apis, (list, tuple)):
        return None
    return {"assist": ASSIST_API in apis, "dashboards": DASHBOARDS_API in apis}


def _clip(value):
    value = str(value)
    return value if len(value) <= MAX_STATE_CHARS else value[: MAX_STATE_CHARS - 1] + "…"


def screen_prompt(home_name, dashboard_title, url_path, view_title=None, on_screen=(), session_id=None):
    """What the agent knows about this turn beyond its own instructions and its tools: the person is
    looking at a dashboard, which one and which view, and [on_screen], `(entity_id, name, state)` for
    the entities its cards show. [session_id] is the edit session open on it, if any: drafts go
    there."""
    where = f"the dashboard '{dashboard_title or 'Overview'}'"
    if view_title:
        where += f", on its '{view_title}' view"
    path = url_path if url_path else "(none: the default Overview dashboard, omit url_path)"
    parts = [
        f"The person is chatting with you from Terrazzo, the Home Assistant app, in their home "
        f"'{home_name}', while looking at {where}. Its url_path is {path}.",
        "They may ask you to control or read their devices, or to change this dashboard. Control "
        "devices with your Assist tools, as you always would. Change the dashboard with Terrazzo's "
        "dashboard tools; nothing is saved until the person applies the draft, which they see drawn "
        "in place on the dashboard they are looking at.",
    ]
    if session_id:
        parts.append(
            f"An edit session is open on this dashboard: session_id {session_id}. Read it with "
            "get_edit_session and submit_draft to that session_id. Don't start another one."
        )
    else:
        parts.append(
            "To change this dashboard, get_dashboard with that url_path, then submit_draft the "
            "complete new config without a session_id, with the revision get_dashboard returned: "
            "that opens the edit session. Change another dashboard only when the person names it."
        )
    if on_screen:
        rows = "\n".join(
            f"- {entity_id} ({_clip(name) if name else entity_id}): {_clip(state)}"
            for entity_id, name, state in on_screen
        )
        parts.append(
            "The cards on the person's screen show these entities, with their state now. When the "
            "person says 'this', 'these' or 'here', they mean these:\n" + rows
        )
    parts.append(
        "Reply briefly in plain text (no Markdown headings or code blocks). After a change to "
        "devices, say what you did. After a draft, say what you changed; don't paste configs."
    )
    return "\n\n".join(parts)


def for_chat(name, arguments, url_path, session_id):
    """[arguments] for a dashboard tool the chat's agent calls: a draft that names no session and no
    other dashboard goes to the open one, into [session_id] when one is open, else with the open
    dashboard's url_path so `submit_draft` opens a session on it rather than on Overview."""
    if name != "submit_draft" or arguments.get("session_id") is not None:
        return arguments
    if arguments.get("new_dashboard_title"):
        return arguments
    if "url_path" in arguments and (arguments.get("url_path") or None) != (url_path or None):
        return arguments  # Another dashboard the person named.
    if session_id:
        return {**{k: v for k, v in arguments.items() if k not in ("url_path", "revision")}, "session_id": session_id}
    if url_path:
        return {**arguments, "url_path": url_path}
    return arguments


def drafted_session(name, result):
    """The edit session a dashboard tool's [result] drafted into or opened, else None."""
    if name not in ("submit_draft", "start_edit_session") or not isinstance(result, dict):
        return None
    if "error" in result:
        return None
    session_id = result.get("session_id")
    return session_id if isinstance(session_id, str) and session_id else None


def reply(speech, response_type, conversation_id, session_id, drafted, steps):
    """What `terrazzo/assist` answers: the agent's words (a stand-in when it only drafted), whether
    it failed, the conversation to carry on, and the edit session its draft is in."""
    text = (speech or "").strip()
    if not text and drafted:
        text = "I've drafted that on the dashboard."
    return {
        "speech": text,
        "error": response_type == "error",
        "conversation_id": conversation_id if isinstance(conversation_id, str) else None,
        "session_id": session_id,
        "drafted": bool(drafted),
        "steps": steps,
    }
