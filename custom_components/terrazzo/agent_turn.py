"""One turn of the conversation agent that answers an edit session: what it is told, what it answers,
and how its steps read in the person's chat. `dashboard_agent.py` runs the turn in Home Assistant.

Free of Home Assistant imports, like `mcp.py` and `edit_sessions.py`, so it is tested on its own
(home-assistant/test/agent_turn_test.py).

The agent is one of Home Assistant's own (OpenAI, Gemini, Anthropic, Ollama, …), reached with
`conversation.async_converse`. It knows the dashboard tools from the "Terrazzo dashboards" LLM API
(`llm_tools.API_PROMPT`); what this adds, as the turn's extra system prompt, is which session it is
answering, what the session may change, and the exchange so far. Each turn is a new conversation:
Home Assistant forgets one after a few idle minutes, and an edit session lasts days, so the recap
here is the agent's memory, not Home Assistant's.
"""
# A recap past this is cut from the oldest end: the newest exchange matters most.
MAX_RECAP_CHARS = 12_000
MAX_REPLY_CHARS = 2000

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


def extra_system_prompt(session, home_name):
    """What the agent knows about this turn beyond its own instructions and the dashboard tools:
    the session, its scope, how to reply, and the exchange before the notes it answers now."""
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
    if recap:
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

