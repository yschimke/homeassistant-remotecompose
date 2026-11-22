"""The built-in dashboard agent: an LLM that Home Assistant calls itself to work edit sessions.

With an API key in the integration's options, every edit session a person starts is answered here
instead of by an agent they connect: each note they leave runs one turn. A turn rebuilds the
conversation from the session, lets the model call a few tools (the same ones the MCP server
offers, bound to the session and to the person who started it), and ends with what the model
says, posted as the agent's message. Like any agent it only drafts: a person applies.

Free of Home Assistant imports, like `mcp.py` and `edit_sessions.py`: `assistant_hass.py` supplies
the model call and the home, and the tests drive this with fakes.

Every provider here speaks OpenAI's chat completions with tool calling: Gemini through Google's
OpenAI-compatible endpoint, OpenRouter natively, and any other compatible service at its own URL.
"""
import json

try:
    from . import mcp
except ImportError:  # Loaded on its own by the tests, beside mcp.py.
    import importlib.util
    from pathlib import Path

    _spec = importlib.util.spec_from_file_location("terrazzo_mcp", Path(__file__).with_name("mcp.py"))
    mcp = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(mcp)

# provider -> how to reach it. A None base_url is the person's own (an OpenAI-compatible service).
PROVIDERS = {
    "gemini": {
        "label": "Google Gemini",
        "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
        # Google's alias for its current Flash model, so the default doesn't go stale.
        "default_model": "gemini-flash-latest",
    },
    "openrouter": {
        "label": "OpenRouter",
        "base_url": "https://openrouter.ai/api/v1",
        # OpenRouter's router picks a model that takes tools; set one for predictable results.
        "default_model": "openrouter/auto",
    },
    "openai_compatible": {
        "label": "Other OpenAI-compatible service",
        "base_url": None,
        "default_model": None,
    },
}

# A turn is a handful of lookups and a draft; past this the model is going round in circles.
MAX_STEPS = 12
# What one tool result may put in front of the model (a large dashboard is still well under).
MAX_TOOL_RESULT_CHARS = 120_000
MAX_REPLY_CHARS = 2000


class AgentConfig:
    """The provider, key, model and URL from the options; [enabled] when a turn can run."""

    def __init__(self, provider, api_key, model="", base_url=""):
        self.provider = provider if provider in PROVIDERS else None
        self.api_key = api_key or ""
        known = PROVIDERS.get(self.provider) or {}
        self.model = (model or "").strip() or known.get("default_model") or ""
        self.base_url = ((base_url or "").strip() or known.get("base_url") or "").rstrip("/")

    @property
    def enabled(self):
        return bool(self.provider and self.api_key and self.model and self.base_url)

    @property
    def label(self):
        return PROVIDERS[self.provider]["label"] if self.provider else ""

    def info(self):
        """What a client may know: whether it's on and which model answers. Never the key."""
        return {
            "enabled": self.enabled,
            "provider": self.provider if self.enabled else None,
            "provider_label": self.label if self.enabled else None,
            "model": self.model if self.enabled else None,
        }


class ModelError(Exception):
    """The model's service refused or failed; the message is shown to the person."""


def _function(name, description, properties=None, required=()):
    """A tool in OpenAI's shape. One without arguments has no parameters at all, and none has an
    object without properties: Gemini refuses both."""
    function = {"name": name, "description": description}
    if properties:
        function["parameters"] = {"type": "object", "properties": properties, "required": list(required)}
    return {"type": "function", "function": function}


_ENTITY_ID = {"entity_id": {"type": "string", "description": "An entity id, e.g. sensor.kitchen_temperature."}}

TOOLS = [
    _function(
        "find_entities",
        "Finds this home's entities by name, best match first, with their current state. Call it "
        "to turn what the person said into entity ids; never invent an entity id.",
        {
            "query": {"type": "string", "description": "Words to match, e.g. 'kitchen light'. Omit to list."},
            "domain": {"type": "string", "description": "Only this domain, e.g. light, sensor, climate."},
        },
    ),
    _function(
        "get_entity_state",
        "Reads one entity's current state, unit and attributes.",
        _ENTITY_ID,
        ["entity_id"],
    ),
    _function(
        "home_overview",
        "Summarizes this home: its areas with the entities in each, and how many entities each "
        "domain has. Use it to plan a new dashboard or a section about a room.",
    ),
    _function(
        "find_designs",
        "Lists harc's card designs made for an entity's device or kind (a garage door drawn as the "
        "door itself, a printer's own controls), best fit first, each with the card config that "
        "draws it. Call it on a device's main entity when the person wants a device to look like "
        "the real thing or asks for a design made for it, and put a design's config in the draft as "
        "it is. An empty list means Home Assistant's own cards are right for that entity.",
        _ENTITY_ID,
        ["entity_id"],
    ),
    _function(
        "list_dashboards",
        "Lists this home's dashboards, to borrow a layout or cards from another one.",
    ),
    _function(
        "get_dashboard",
        "Reads another dashboard's configuration.",
        {"url_path": {"type": "string", "description": "From list_dashboards; omit for Overview."}},
    ),
    _function(
        "submit_draft",
        "Submits the complete new dashboard config as a draft, with a one-line summary. The "
        "person sees it at once drawn with their own cards and live states, and applies it "
        "themselves; nothing is saved by this call. Every entity it names must exist. Refused "
        "drafts say why: fix them and submit again.",
        {
            "config_json": {
                "type": "string",
                "description": "The whole dashboard as JSON text: an object with a views list, "
                "keeping every view, section and card the person didn't ask to change.",
            },
            "summary": {"type": "string", "description": "One line: what changed."},
        },
        ["config_json", "summary"],
    ),
]

# What the person sees the agent doing while a tool runs.
_STATUS = {
    "find_entities": "Looking up entities",
    "get_entity_state": "Reading an entity",
    "home_overview": "Looking around the home",
    "find_designs": "Looking for card designs",
    "list_dashboards": "Reading dashboards",
    "get_dashboard": "Reading a dashboard",
    "submit_draft": "Drafting",
}


def system_prompt(session, home_name, current_config):
    """What the model knows before the conversation: its job, the card guide, the scope, and the
    dashboard as it stands (the newest draft, else the dashboard as it is now)."""
    scope = session.get("scope") or {}
    kind = scope.get("kind")
    if kind == "new_dashboard":
        about = (
            f"You are building a NEW dashboard titled '{scope.get('title') or 'New dashboard'}' "
            "from scratch. Start from the person's request; use home_overview and find_entities "
            "to choose this home's entities, group them into sections by room or purpose, and "
            "submit a complete first draft early rather than asking many questions."
        )
    elif kind == "section":
        about = (
            f"This session is about one section only, {scope.get('label') or 'the section'} "
            f"(views[{scope.get('view')}].sections[{scope.get('section')}]). Change only that "
            "section and keep the rest of the dashboard exactly as it is, or the draft is refused."
        )
    else:
        about = (
            "You may change the dashboard's structure (views, sections, their order) and any of "
            "its cards. When a note is pinned to a card, it is about that card."
        )
    return "\n\n".join(
        [
            f"You edit Home Assistant dashboards for the person you are chatting with, in their "
            f"home '{home_name}', for Terrazzo (harc), which draws Lovelace dashboards natively. "
            "You can't save a dashboard: you submit drafts with submit_draft, the person sees "
            "each draft live and applies it themselves. Reply briefly in plain text (no "
            "Markdown headings or code blocks): say what you changed, or ask one short question "
            "when the request is unclear. Don't paste configs into replies.",
            about,
            "Card guide:\n" + json.dumps(mcp.CARD_GUIDE, ensure_ascii=False),
            "The dashboard as it stands now (your newest draft if there is one, otherwise the "
            "saved dashboard). Edit this, and always submit the whole config:\n"
            + json.dumps(current_config, ensure_ascii=False, default=str),
        ]
    )


def conversation(session):
    """The session as chat turns: the request, then the person's notes and the agent's messages
    and drafts in the order they happened. Same-role neighbours are merged, which every
    OpenAI-compatible service accepts."""
    said = []
    for note in session.get("notes", []):
        text = note["text"]
        if note.get("target"):
            text = f"(About the card {note['target']}) {text}"
        said.append((note.get("at", 0), note.get("seq", 0), "user", text))
    for message in session.get("messages", []):
        said.append((message.get("at", 0), message.get("seq", 0), "assistant", message["text"]))
    for draft in session.get("drafts", []):
        said.append((draft.get("at", 0), 0, "assistant", f"[I submitted a draft: {draft.get('summary') or 'no summary'}]"))
    said.sort(key=lambda item: (item[0], item[1]))
    turns = [{"role": "user", "content": session.get("request") or "Hello."}]
    for _, _, role, text in said:
        if turns[-1]["role"] == role:
            turns[-1]["content"] += "\n\n" + text
        else:
            turns.append({"role": role, "content": text})
    if turns[-1]["role"] != "user":
        # Nothing new from the person (a turn only runs when there is): nudge rather than fail.
        turns.append({"role": "user", "content": "Carry on."})
    return turns


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


def _clip(text, limit):
    return text if len(text) <= limit else text[: limit - 1] + "…"


async def _run_tool(home, session_id, name, arguments):
    """One tool call, as the MCP server would run it for this session; errors go back to the
    model as text so it can correct itself."""
    try:
        if name == "home_overview":
            return home.overview()
        if name == "submit_draft":
            config = arguments.get("config_json", arguments.get("config"))
            if isinstance(config, str):
                try:
                    config = json.loads(config)
                except ValueError as failure:
                    return {"error": f"config_json isn't valid JSON: {failure}"}
            if not isinstance(config, dict):
                return {"error": "config_json must be the dashboard config object, as JSON"}
            summary = arguments.get("summary")
            return await home.submit_draft(session_id, config, summary if isinstance(summary, str) else "")
        if name in ("find_entities", "get_entity_state", "find_designs", "list_dashboards", "get_dashboard"):
            return await mcp.call_tool(home, name, arguments)
        return {"error": f"Unknown tool {name}"}
    except mcp.ToolError as failure:
        return {"error": str(failure)}


def _arguments(call):
    raw = (call.get("function") or {}).get("arguments")
    if isinstance(raw, dict):
        return raw
    try:
        parsed = json.loads(raw or "{}")
    except (TypeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


async def run_turn(session, home, complete, set_status=None, max_steps=MAX_STEPS, on_step=None):
    """Answer the person's newest notes in [session]: returns the reply to post (may be empty).

    [complete] is `async (messages, tools) -> assistant message` in OpenAI's shape, [home] is the
    MCP server's home bound to the person (with `overview()` and a session-aware
    `submit_draft`), [set_status] shows what the agent is doing, and [on_step] gets each tool
    call as `(tool, arguments, output, ok)` in words (`describe_step`) for the person's chat."""
    current = await home.current_config(session)
    messages = [{"role": "system", "content": system_prompt(session, home.name, current)}]
    messages += conversation(session)
    drafted = False
    for _ in range(max_steps):
        reply = await complete(messages, TOOLS)
        calls = reply.get("tool_calls") or []
        content = reply.get("content")
        text = content if isinstance(content, str) else ""
        assistant = {"role": "assistant", "content": text or None}
        if calls:
            assistant["tool_calls"] = calls
        messages.append(assistant)
        if not calls:
            return _clip(text.strip(), MAX_REPLY_CHARS) or (
                "I've submitted a draft." if drafted else ""
            )
        for call in calls:
            name = (call.get("function") or {}).get("name") or ""
            arguments = _arguments(call)
            if set_status is not None and name in _STATUS:
                set_status(_STATUS[name])
            if arguments is None:
                result = {"error": "The arguments weren't a JSON object"}
            else:
                result = await _run_tool(home, session["id"], name, arguments)
                if name == "submit_draft" and "error" not in result:
                    drafted = True
            if on_step is not None:
                on_step(name, *describe_step(name, arguments, result))
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call.get("id") or name,
                    "name": name,
                    "content": _clip(json.dumps(result, ensure_ascii=False, default=str), MAX_TOOL_RESULT_CHARS),
                }
            )
    return "I submitted what I have so far." if drafted else (
        "I couldn't finish that in one go. Tell me which part to start with."
    )


def completion_request(config, messages, tools):
    """The chat completions call for [config]: (url, headers, body)."""
    headers = {"Authorization": f"Bearer {config.api_key}", "Content-Type": "application/json"}
    if config.provider == "openrouter":
        headers["X-Title"] = "Terrazzo"
    body = {"model": config.model, "messages": messages, "tools": tools, "tool_choice": "auto"}
    return f"{config.base_url}/chat/completions", headers, body


def completion_message(status, payload):
    """The assistant message from a chat completions reply, or ModelError saying what went wrong."""
    if status in (401, 403):
        raise ModelError("The agent's API key was refused. Check it under Terrazzo → Configure.")
    if status == 429:
        raise ModelError("The agent's service is rate limiting this key. Try again in a minute.")
    error = payload.get("error") if isinstance(payload, dict) else None
    if status != 200 or error:
        detail = error.get("message") if isinstance(error, dict) else error
        raise ModelError(f"The agent's service answered {status}: {_clip(str(detail or 'no detail'), 300)}")
    choices = payload.get("choices") if isinstance(payload, dict) else None
    if not choices or not isinstance(choices[0], dict) or not isinstance(choices[0].get("message"), dict):
        raise ModelError("The agent's service sent no reply.")
    return choices[0]["message"]
