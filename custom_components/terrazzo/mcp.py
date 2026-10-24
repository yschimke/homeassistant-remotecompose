"""The MCP protocol and tools for one Home Assistant instance, free of Home Assistant imports.

`mcp_view.py` serves this over MCP's Streamable HTTP transport and supplies the `home` object; the
tests drive it with a fake one. Only stateless request/response JSON is spoken: no sessions, no
server-sent events, no batching, which every current MCP client accepts.
"""
import json

# Newest first; an older client gets its own version back when this server speaks it.
PROTOCOL_VERSIONS = ("2025-11-25", "2025-06-18", "2025-03-26")

# An agent may read these but never switch them, the same rule as the Android app's AppFunctions.
READ_ONLY_DOMAINS = frozenset({"lock", "alarm_control_panel"})
SEARCH_LIMIT = 25

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602


class ToolError(Exception):
    """A tool's failure, reported to the agent as an `isError` result rather than a protocol error."""


class Unauthorized(ToolError):
    """Home Assistant's permissions don't let the pinned user do this."""


def _entity_id_schema(description):
    return {
        "type": "object",
        "properties": {"entity_id": {"type": "string", "description": description}},
        "required": ["entity_id"],
        "additionalProperties": False,
    }


_SWITCH_NOTE = (
    " Returns the entity as it was before the call; Home Assistant reports the new state shortly"
    " after. Locks and alarm panels are refused."
)

TOOLS = [
    {
        "name": "find_entities",
        "title": "Find entities",
        "description": (
            "Finds this Home Assistant's entities (lights, switches, sensors, scenes, scripts and "
            "others) by name, best match first, with their current state. Call it first to turn "
            "what the user said into an entity_id."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Words to match in the name or entity id, e.g. 'kitchen light'. "
                    "Omit to list entities.",
                },
                "domain": {
                    "type": "string",
                    "description": "Only this domain, e.g. 'light', 'switch', 'sensor', 'scene'.",
                },
            },
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "get_entity_state",
        "title": "Get entity state",
        "description": "Reads one entity's current state, unit and attributes.",
        "inputSchema": _entity_id_schema("The entity id, e.g. 'sensor.living_room_temperature'."),
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "turn_on",
        "title": "Turn on",
        "description": "Turns an entity on (a light, switch, fan or similar), or runs a scene or script."
        + _SWITCH_NOTE,
        "inputSchema": _entity_id_schema("The entity id, e.g. 'light.kitchen' or 'scene.movie'."),
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "turn_off",
        "title": "Turn off",
        "description": "Turns an entity off (a light, switch, fan or similar)." + _SWITCH_NOTE,
        "inputSchema": _entity_id_schema("The entity id, e.g. 'light.kitchen'."),
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "toggle",
        "title": "Toggle",
        "description": "Toggles an entity between on and off." + _SWITCH_NOTE,
        "inputSchema": _entity_id_schema("The entity id, e.g. 'switch.coffee_machine'."),
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "list_dashboards",
        "title": "List dashboards",
        "description": (
            "Lists this Home Assistant's Lovelace dashboards, with each one's link in the Terrazzo "
            "panel."
        ),
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "get_dashboard",
        "title": "Get dashboard",
        "description": (
            "Reads a Lovelace dashboard's configuration: its views and cards, and the entities "
            "they show."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "url_path": {
                    "type": "string",
                    "description": "The dashboard's url_path from list_dashboards. Omit for the "
                    "default Overview dashboard.",
                }
            },
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
]

_SESSION_ID = {
    "type": "string",
    "description": "The edit session's id, from the person's prompt or start_edit_session.",
}

EDIT_TOOLS = [
    {
        "name": "start_edit_session",
        "title": "Start a dashboard edit",
        "description": (
            "Opens an edit session on a dashboard for a change the user asked for. Drafts you "
            "submit to it are previewed and applied by a person; nothing you do saves the "
            "dashboard. Skip this when the user gave you a session id."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "url_path": {
                    "type": "string",
                    "description": "The dashboard's url_path from list_dashboards. Omit for the "
                    "default Overview dashboard.",
                },
                "request": {"type": "string", "description": "What the user wants changed."},
            },
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
    {
        "name": "get_edit_session",
        "title": "Get a dashboard edit",
        "description": (
            "Reads an edit session: the request, the dashboard's current config, your latest draft, "
            "and the person's notes on it. Read the notes before submitting again."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"session_id": _SESSION_ID},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "get_card_guide",
        "title": "Get the card guide",
        "description": (
            "How to write a dashboard harc draws well: the card types it draws natively, how to "
            "size cards, and where to see harc's card designs."
        ),
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        "annotations": {"readOnlyHint": True, "openWorldHint": False},
    },
    {
        "name": "submit_draft",
        "title": "Submit a dashboard draft",
        "description": (
            "Submits the complete new dashboard config to an edit session, with a one-line summary "
            "of the change. A person previews it with live cards and applies it; it isn't saved by "
            "this call. Every entity it names must exist in this home."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": _SESSION_ID,
                "config": {
                    "type": "object",
                    "description": "The whole dashboard config: an object with a views list, "
                    "keeping every view, section and card the user didn't ask to change.",
                },
                "summary": {"type": "string", "description": "One line: what changed."},
            },
            "required": ["session_id", "config"],
            "additionalProperties": False,
        },
        "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
    },
]
TOOLS += EDIT_TOOLS

# What an agent reads before drafting (get_card_guide). skills/ha-dashboard/SKILL.md says the same
# for agents that load skills; change them together.
CARD_GUIDE = {
    "native_card_types": [
        "tile", "entities", "glance", "gauge", "sensor", "thermostat", "humidifier", "light",
        "button", "entity", "history-graph", "statistics-graph", "statistic", "weather-forecast",
        "media-control", "alarm-panel", "area", "picture-entity", "markdown", "heading",
        "todo-list", "calendar", "logbook", "map", "clock", "vertical-stack", "horizontal-stack",
        "grid", "conditional", "entity-filter",
    ],
    "rules": [
        "Submit the complete dashboard config; keep every view, section and card the user "
        "didn't ask to change.",
        "Use only entity ids from find_entities. Never invent entities.",
        "Prefer the native card types; other (custom) cards show as a plain list of entities.",
        'Lay views out with "type": "sections" and group related cards into sections that start '
        "with a heading card. harc shows sections in one column on a phone, side by side wider.",
        "Give a card a short name or title when the entity's own name is unclear.",
        "Don't add automations, scripts, scenes or helpers: this only edits dashboards.",
    ],
    "sizing": [
        'Each section is a 12-column grid. Size a card with "grid_options": {"columns": N, '
        '"rows": R}: columns 3, 6, 9 or "full", rows a number of 56 dp rows or "auto".',
        "On a phone a section is about 400 dp wide: two tiles share a row at 6 columns each; "
        'gauges, graphs, forecasts and lists read best "full".',
        "Cards fill a section's rows in order, so order them the way they should read.",
    ],
    "designs": (
        "harc's card designs are published in the hosted Compose Preview catalog, an MCP server "
        "at https://preview.coo.ee/mcp (catalog homeassistant-remotecompose; reading needs a "
        "grant from request_access / poll_access). catalog_list_previews with query card-<type> "
        "lists a type's designs; catalog_render_preview (observe png) shows one, and its device "
        "override or catalog_render_matrix shows it at other widths."
    ),
}

TOOL_NAMES = frozenset(tool["name"] for tool in TOOLS)
_SWITCH_SERVICES = {"turn_on": "turn_on", "turn_off": "turn_off", "toggle": "toggle"}


def domain_of(entity_id):
    return entity_id.split(".", 1)[0] if "." in entity_id else ""


def name_of(entity):
    name = (entity.get("attributes") or {}).get("friendly_name")
    return name if isinstance(name, str) and name.strip() else entity["entity_id"]


def search(entities, query=None, domain=None, limit=SEARCH_LIMIT):
    """Every word of [query] in the name or id, in [domain]; exact names first, then prefixes."""
    wanted = (query or "").strip().casefold()
    words = wanted.split()
    in_domain = (domain or "").strip().casefold() or None

    def rank(entity):
        name = name_of(entity).casefold()
        if not wanted:
            tier = 2
        elif name == wanted or entity["entity_id"].casefold() == wanted:
            tier = 0
        elif name.startswith(wanted):
            tier = 1
        else:
            tier = 2
        return (tier, name, entity["entity_id"])

    matches = [
        entity
        for entity in entities
        if (in_domain is None or domain_of(entity["entity_id"]) == in_domain)
        and all(word in f"{name_of(entity).casefold()} {entity['entity_id'].casefold()}" for word in words)
    ]
    return sorted(matches, key=rank)[:limit]


def write_refusal(entity_id):
    domain = domain_of(entity_id)
    if domain in READ_ONLY_DOMAINS:
        return f"{entity_id} is a {domain}; Terrazzo doesn't let assistants change it"
    return None


def summary(entity, with_attributes=False):
    attributes = entity.get("attributes") or {}
    unit = attributes.get("unit_of_measurement")
    result = {
        "entity_id": entity["entity_id"],
        "name": name_of(entity),
        "domain": domain_of(entity["entity_id"]),
        "state": entity["state"],
        "unit": unit if isinstance(unit, str) and unit else None,
        "can_switch": write_refusal(entity["entity_id"]) is None,
    }
    if with_attributes:
        result["attributes"] = attributes
        result["last_changed"] = entity.get("last_changed")
    return result


def _entity_id(arguments):
    entity_id = arguments.get("entity_id")
    if not isinstance(entity_id, str) or not domain_of(entity_id):
        raise ToolError("entity_id must be an entity id like light.kitchen")
    return entity_id


def _optional_text(arguments, key):
    value = arguments.get(key)
    if value is not None and not isinstance(value, str):
        raise ToolError(f"{key} must be a string")
    return value or ""


def _session_id(arguments):
    session_id = arguments.get("session_id")
    if not isinstance(session_id, str) or not session_id:
        raise ToolError("session_id must be the edit session's id")
    return session_id


def _entity(home, arguments):
    entity_id = _entity_id(arguments)
    entity = home.state(entity_id)
    if entity is None:
        raise ToolError(f"No entity {entity_id}; use find_entities")
    return entity


async def call_tool(home, name, arguments):
    """Runs tool [name]; returns its structured result or raises ToolError."""
    if name == "find_entities":
        matches = search(home.states(), arguments.get("query"), arguments.get("domain"))
        return {"entities": [summary(entity) for entity in matches]}
    if name == "get_entity_state":
        return summary(_entity(home, arguments), with_attributes=True)
    if name in _SWITCH_SERVICES:
        entity_id = _entity_id(arguments)
        if refusal := write_refusal(entity_id):
            raise ToolError(refusal)
        before = _entity(home, arguments)
        await home.switch(_SWITCH_SERVICES[name], entity_id)
        return summary(before)
    if name == "list_dashboards":
        return {"dashboards": home.dashboards()}
    if name == "start_edit_session":
        url_path = _optional_text(arguments, "url_path")
        return await home.start_edit_session(url_path or None, _optional_text(arguments, "request"))
    if name == "get_edit_session":
        return await home.edit_session(_session_id(arguments))
    if name == "get_card_guide":
        return CARD_GUIDE
    if name == "submit_draft":
        config = arguments.get("config")
        if not isinstance(config, dict):
            raise ToolError("config must be the dashboard config object")
        return await home.submit_draft(
            _session_id(arguments), config, _optional_text(arguments, "summary")
        )
    if name == "get_dashboard":
        url_path = arguments.get("url_path")
        if url_path is not None and not isinstance(url_path, str):
            raise ToolError("url_path must be a string")
        return {"url_path": url_path or None, "config": await home.dashboard(url_path or None)}
    raise ToolError(f"Unknown tool {name}")


def _result(message_id, result):
    return {"jsonrpc": "2.0", "id": message_id, "result": result}


def _error(message_id, code, text):
    return {"jsonrpc": "2.0", "id": message_id, "error": {"code": code, "message": text}}


async def handle(message, home, server_version):
    """One JSON-RPC message in, its response out: None for a notification or a client response."""
    if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
        return _error(None, INVALID_REQUEST, "Expected one JSON-RPC 2.0 message")
    method = message.get("method")
    message_id = message.get("id")
    if not isinstance(method, str):
        # A response to a server request; this server never sends one.
        return None
    if "id" not in message:
        return None  # notifications/initialized and the like need no answer.
    params = message.get("params") or {}
    if not isinstance(params, dict):
        return _error(message_id, INVALID_PARAMS, "params must be an object")

    if method == "initialize":
        requested = params.get("protocolVersion")
        version = requested if requested in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0]
        return _result(
            message_id,
            {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "terrazzo", "title": "Terrazzo", "version": server_version},
                "instructions": (
                    f"Controls one private Home Assistant instance, '{home.name}', as the user "
                    "Terrazzo's MCP server is pinned to, with that user's permissions. Use "
                    "find_entities to resolve names to entity ids before reading or switching. "
                    "To change a dashboard, work in an edit session (get_edit_session, "
                    "get_card_guide, submit_draft): you submit drafts and a person applies them."
                ),
            },
        )
    if method == "ping":
        return _result(message_id, {})
    if method == "tools/list":
        return _result(message_id, {"tools": TOOLS})
    if method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if not isinstance(arguments, dict):
            return _error(message_id, INVALID_PARAMS, "arguments must be an object")
        if not isinstance(name, str) or name not in TOOL_NAMES:
            return _error(message_id, INVALID_PARAMS, f"Unknown tool {name}")
        try:
            structured = await call_tool(home, name, arguments)
        except ToolError as failure:
            return _result(message_id, {"content": [{"type": "text", "text": str(failure)}], "isError": True})
        return _result(
            message_id,
            {
                "content": [{"type": "text", "text": json.dumps(structured, ensure_ascii=False, default=str)}],
                "structuredContent": structured,
            },
        )
    return _error(message_id, METHOD_NOT_FOUND, f"Unknown method {method}")
