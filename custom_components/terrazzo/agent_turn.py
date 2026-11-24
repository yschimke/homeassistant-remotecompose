"""One turn of the conversation agent that answers an edit session: what it is told, what it answers,
and how its steps read in the person's chat. `dashboard_agent.py` runs the turn in Home Assistant.

Free of Home Assistant imports, like `mcp.py` and `edit_sessions.py`, so it is tested on its own
(home-assistant/test/agent_turn_test.py).

The agent is one of Home Assistant's own (OpenAI, Gemini, Anthropic, Ollama, …), reached with
`conversation.async_converse`. It knows the dashboard tools from the "Terrazzo dashboards" LLM API
(`llm_tools.API_PROMPT`); what this adds, as the turn's extra system prompt, is which session it is
answering, what the session may change, the household's standing notes (`agent_memory.py`) and
the exchange so far. A turn soon after the last one carries on its conversation, which already
holds that exchange (`edit_sessions.warm_conversation`); otherwise it is a new one: Home Assistant
forgets a conversation after a few idle minutes, and an edit session lasts days, so the recap here
is then the agent's memory, not Home Assistant's.
"""
import re

# A recap past this is cut from the oldest end: the newest exchange matters most.
MAX_RECAP_CHARS = 12_000
MAX_REPLY_CHARS = 2000

# Seconds to wait before each retry of a turn the provider refused for want of capacity.
RETRY_DELAYS = (2, 5, 12)
# A provider's words for a passing lack of capacity: HTTP 503 / 429 and gRPC UNAVAILABLE /
# RESOURCE_EXHAUSTED (Gemini), overloaded (Anthropic), rate limits (OpenAI). Not a bare
# "unavailable": an entity can be unavailable too.
_TRANSIENT = re.compile(
    r"\b(503|429)\b|service unavailable|resource[_ ]exhausted|overloaded"
    r"|rate.?limit|too many requests|try again later",
    re.IGNORECASE,
)


def transient(text):
    """Whether a provider's failure [text] reads as a passing lack of capacity: worth the same turn
    again after a pause."""
    return bool(_TRANSIENT.search(str(text or "")))


# What the chat's status line says while the agent calls a tool.
STATUS = {
    "find_entities": "Looking for entities",
    "get_entity_state": "Reading an entity",
    "home_overview": "Looking around the home",
    "find_designs": "Looking for card designs",
    "list_dashboards": "Reading dashboards",
    "get_dashboard": "Reading a dashboard",
    "get_edit_session": "Reading the dashboard",
    "get_card_guide": "Reading the card guide",
    "submit_draft": "Drafting",
}


def _scope(session):
    scope = session.get("scope") or {}
    kind = scope.get("kind")
    if kind == "new_dashboard":
        return (
            f"It builds a NEW dashboard titled '{scope.get('title') or 'New dashboard'}' from "
            "scratch. Use home_overview and find_entities to choose this home's entities, group "
            "them into sections by room or purpose, and submit a complete first draft early rather "
            "than asking many questions."
        )
    if kind == "section":
        return (
            f"It is about one section only, {scope.get('label') or 'the section'} "
            f"(views[{scope.get('view')}].sections[{scope.get('section')}]). Change only that "
            "section and keep the rest of the dashboard exactly as it is, or the draft is refused."
        )
    return (
        "You may change the dashboard's structure (views, sections, their order) and any of its "
        "cards. When a note is about a card, change that card."
    )


def _said(session):
    """The session's exchange in order: (seq, who, text) for the person's notes, the agent's
    messages and its drafts."""
    said = []
    for note in session.get("notes", []):
        text = note["text"]
        if note.get("target"):
            text = f"(About the card {note['target']}) {text}"
        said.append((note.get("at", 0), note.get("seq", 0), "Person", text))
    for message in session.get("messages", []):
        said.append((message.get("at", 0), message.get("seq", 0), "You", message["text"]))
    for draft in session.get("drafts", []):
        said.append((draft.get("at", 0), 0, "You", f"[submitted a draft: {draft.get('summary') or 'no summary'}]"))
    said.sort(key=lambda item: (item[0], item[1]))
    return said


def _answered(session):
    """The newest note seq the agent has taken, or None before its first turn."""
    return (session.get("assistant") or {}).get("handled")


MAX_HOUSE_NOTES_CHARS = 2000

# The household's notes (`agent_memory.py`): one for the home, one per dashboard.
MAX_NOTE_CHARS = 2000
MAX_DASHBOARDS = 100


def clean_note(text):
    """[text] as a stored note: trimmed, at most `MAX_NOTE_CHARS`, empty for none."""
    if text is None:
        return ""
    if not isinstance(text, str):
        raise ValueError("A note is text")
    return text.strip()[:MAX_NOTE_CHARS]


def notes_for(data, url_path):
    """The notes a turn on dashboard [url_path] gets: the home's, then that dashboard's."""
    home = (data or {}).get("home") or ""
    dashboard = ((data or {}).get("dashboards") or {}).get(url_path or "") or ""
    parts = []
    if home:
        parts.append(home)
    if dashboard:
        parts.append(f"For this dashboard: {dashboard}")
    return "\n".join(parts)


def with_note(data, url_path, text, home=False):
    """[data] with one note set ([home] for the whole home's, else [url_path]'s); empty clears it."""
    data = {"home": (data or {}).get("home") or "", "dashboards": dict((data or {}).get("dashboards") or {})}
    note = clean_note(text)
    if home:
        data["home"] = note
    elif note:
        if (url_path or "") not in data["dashboards"] and len(data["dashboards"]) >= MAX_DASHBOARDS:
            raise ValueError(f"At most {MAX_DASHBOARDS} dashboards can have notes")
        data["dashboards"][url_path or ""] = note
    else:
        data["dashboards"].pop(url_path or "", None)
    return data



def extra_system_prompt(session, home_name, warm=False, house_notes=None):
    """What the agent knows about this turn beyond its own instructions and the dashboard tools:
    the session, its scope, how to reply, the household's standing notes, and the exchange before
    the notes it answers now. [warm] when the turn carries on the last turn's conversation, which
    already holds that exchange: the recap is left out, so the prompt stays the same between turns
    and the provider can reuse its cache."""
    handled = _answered(session)
    earlier = [
        f"{who}: {text}"
        for _, seq, who, text in _said(session)
        if handled is not None and (who != "Person" or seq <= handled)
    ]
    recap = "\n".join(earlier)
    if len(recap) > MAX_RECAP_CHARS:
        recap = "…" + recap[-(MAX_RECAP_CHARS - 1):]
    parts = [
        f"You are answering a dashboard edit session in the Terrazzo panel or app, for the person "
        f"you are chatting with in their home '{home_name}'. Its session_id is {session['id']}: "
        "read it with get_edit_session first (the dashboard as it stands, your latest draft and "
        "the person's notes), then submit_draft the complete new config to that session_id. "
        "Don't start another edit session.",
        f"The session's request: {session.get('request') or '(none)'}",
        _scope(session),
        "The person sees each draft drawn live with their own cards and applies it themselves. "
        "Reply briefly in plain text (no Markdown headings or code blocks): say what you changed, "
        "or ask one short question when the request is unclear. Don't paste configs.",
    ]
    notes = (house_notes or "").strip()
    if notes:
        if len(notes) > MAX_HOUSE_NOTES_CHARS:
            notes = notes[:MAX_HOUSE_NOTES_CHARS - 1] + "…"
        parts.append(
            "The household's standing notes for dashboard edits (their preferences; follow them "
            "unless this request says otherwise):\n" + notes
        )
    if recap and not warm:
        parts.append("The conversation so far:\n" + recap)
    return "\n\n".join(parts)


def turn_text(session):
    """What the person said that the agent hasn't answered: the request on a first turn, then the
    notes since its last one."""
    handled = _answered(session)
    if handled is None:
        texts = [session.get("request") or ""]
        texts += [text for _, _, who, text in _said(session) if who == "Person"]
    else:
        texts = [
            text for _, seq, who, text in _said(session) if who == "Person" and seq > handled
        ]
    text = "\n\n".join(t for t in texts if t)
    return text or "Carry on."


def reply_text(speech, drafted):
    """The agent's reply as the session's message: its words, clipped, or a stand-in when it only
    drafted."""
    text = (speech or "").strip()
    if len(text) > MAX_REPLY_CHARS:
        text = text[: MAX_REPLY_CHARS - 1] + "…"
    return text or ("I've submitted a draft." if drafted else "")


def describe_step(name, arguments, result):
    """A tool call as the person sees it in the chat: (what it asked, what it got, whether it
    worked), in a few words rather than the raw JSON."""
    arguments = arguments if isinstance(arguments, dict) else {}
    result = result if isinstance(result, dict) else {}
    if "error" in result:
        return _step_arguments(name, arguments), str(result["error"]), False
    if name == "find_entities":
        found = result.get("entities") or []
        shown = ", ".join(e.get("name") or e.get("entity_id", "") for e in found[:5])
        more = f" and {len(found) - 5} more" if len(found) > 5 else ""
        output = f"{len(found)} found: {shown}{more}" if found else "Nothing found"
    elif name == "get_entity_state":
        unit = result.get("unit") or ""
        output = f"{result.get('name', '')}: {result.get('state', '')}{(' ' + unit) if unit else ''}"
    elif name == "home_overview":
        areas = result.get("areas") or []
        output = f"{len(areas)} areas: " + ", ".join(a.get("area", "") for a in areas[:8])
    elif name == "find_designs":
        designs = result.get("designs") or []
        output = ", ".join(d.get("title", "") for d in designs[:3]) or "Nothing made for it"
    elif name == "list_dashboards":
        dashboards = result.get("dashboards") or []
        output = ", ".join(d.get("title", "") for d in dashboards[:8]) or "None"
    elif name == "get_dashboard":
        views = ((result.get("config") or {}).get("views")) or []
        output = f"{len(views)} view{'' if len(views) == 1 else 's'}"
    elif name == "submit_draft":
        output = "Draft submitted"
    else:
        output = "Done"
    return _step_arguments(name, arguments), output, True


def _step_arguments(name, arguments):
    if name == "submit_draft":
        summary = arguments.get("summary")
        return summary if isinstance(summary, str) else ""
    shown = []
    for key in ("query", "domain", "entity_id", "url_path"):
        value = arguments.get(key)
        if isinstance(value, str) and value:
            shown.append(value)
    return " · ".join(shown)



# Setting the agent up: what Terrazzo tells a person when no agent answers, or the chosen one can't
# reach the dashboard tools. `dashboard_agent.py` reads Home Assistant; these only decide and word.

API_ID = "terrazzo_dashboards"  # llm_tools.API_ID, without importing the MCP tools here.
API_NAME = "Terrazzo dashboards"

# Before 0.1.101 Terrazzo called an AI service itself (`agent_provider`). Each one's Home
# Assistant integration, which now holds the key and the model instead.
MOVED_SERVICES = {
    "gemini": ("Google Gemini", "Google Generative AI"),
    "openrouter": ("OpenRouter", "OpenRouter"),
    "openai_compatible": ("your OpenAI-compatible service", "Ollama, or that service's own"),
}


def moved_placeholders(provider):
    """The repair's words for a built-in agent that was on: the service it called and the
    integration that takes its place. None when it was off or unknown."""
    if not isinstance(provider, str) or provider not in MOVED_SERVICES:
        return None
    service, integration = MOVED_SERVICES[provider]
    return {"service": service, "integration": integration}


def tools_on(apis, definite):
    """Whether an agent's options turn the dashboard tools on: True or False, or None when Terrazzo
    can't tell. [apis] is its `llm_hass_api` (a list of API ids, one id, or absent). Absent means none
    only for a [definite] place, an AI integration's conversation agent; elsewhere an agent may not
    keep its APIs there at all."""
    if apis is None:
        return False if definite else None
    if isinstance(apis, str):
        apis = [apis]
    if not isinstance(apis, (list, tuple)):
        return None
    return API_ID in apis


def no_tools_reply(agent_name):
    """The chat's answer while the chosen agent can't reach the dashboard tools, instead of a turn
    that could only fail."""
    name = agent_name or "This agent"
    return (
        f"I can't edit dashboards yet: {name} doesn't have \"{API_NAME}\" turned on. An administrator "
        f"can turn it on in Settings → Devices & services: open {name}'s integration, open the "
        f"agent's settings (the cog beside it), tick \"{API_NAME}\" under its AI tools and submit. "
        "Then send your request again."
    )
