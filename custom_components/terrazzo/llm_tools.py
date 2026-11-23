"""The dashboard tools as a Home Assistant LLM API: which tools, the prompt, and how a call runs.

Plain data and functions, nothing from Home Assistant, so the rules are tested without it;
`llm_api.py` registers them with `homeassistant.helpers.llm`. A conversation agent the person
already uses (OpenAI, Google Gemini, Anthropic, Ollama, …) can then edit dashboards with its own
provider and key, by choosing "Terrazzo dashboards" beside or instead of Assist in its options, and
Home Assistant's MCP Server can serve the same tools to an outside agent.

The tools are the MCP server's (`mcp.py`), run by the same `mcp.call_tool` as the conversation's
own user, plus `home_overview`. Only what fits a conversation turn: no
switching (Assist does that, for exposed entities), and no long-polling (`await_feedback`,
`await_request`): the person answers in the chat, not in a session's notes.
"""
try:
    from . import mcp
except ImportError:  # Loaded on its own by the tests, beside mcp.py.
    import importlib.util
    from pathlib import Path

    _spec = importlib.util.spec_from_file_location("terrazzo_mcp", Path(__file__).with_name("mcp.py"))
    mcp = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(mcp)

API_ID = "terrazzo_dashboards"
API_NAME = "Terrazzo dashboards"

# The MCP server's tools this API offers, in the order an agent uses them.
MCP_TOOL_NAMES = (
    "list_dashboards",
    "get_dashboard",
    "find_entities",
    "get_entity_state",
    "find_designs",
    "get_card_guide",
    "list_edit_sessions",
    "start_edit_session",
    "get_edit_session",
    "submit_draft",
)

HOME_OVERVIEW = {
    "name": "home_overview",
    "description": (
        "Summarizes this home: its areas with the entities in each, and how many entities each "
        "domain has. Use it to plan a new dashboard or a section about a room."
    ),
    "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    "annotations": {"readOnlyHint": True, "openWorldHint": False},
}

API_PROMPT = "\n".join(
    [
        "You can edit this home's Lovelace dashboards with Terrazzo's dashboard tools.",
        "- To change a dashboard, start_edit_session (or get_edit_session for one already open), "
        "read it, then submit_draft the complete new config with a one-line summary. Nothing is "
        "saved: the person reviews each draft in the Terrazzo panel or app, drawn with their own "
        "cards and live states, and applies it themselves. Tell them where (the session's "
        "terrazzo_url) and what you changed.",
        "- Read get_card_guide before drafting: the card types Terrazzo draws, how to size them, and "
        "how to use a card design made for a device (find_designs), including collapsing a device's "
        "entities into one custom:remote-compose-card with optional bindings, actions, and device_id.",
        "- Use only entity ids from find_entities or home_overview. These tools see every entity "
        "the person may read, not only those exposed to Assist.",
        "- These tools only edit dashboards: they never control devices or change automations.",
    ]
)


def tool_specs():
    """Each tool as `{name, description, inputSchema, annotations}`: the MCP server's, then
    `home_overview`."""
    by_name = {tool["name"]: tool for tool in mcp.TOOLS}
    specs = [
        {k: by_name[name][k] for k in ("name", "description", "inputSchema", "annotations")}
        for name in MCP_TOOL_NAMES
    ]
    return specs + [HOME_OVERVIEW]


def annotations(spec):
    """[spec]'s MCP annotations as Home Assistant's `llm.ToolAnnotations` fields. Home
    Assistant's MCP Server, set to read only, serves only the tools marked read only: these keep
    reading dashboards, entities, designs and sessions, and drop starting a session and drafting.
    None deletes anything; open world is the MCP spec's (find_designs reads the design server)."""
    hints = spec.get("annotations") or {}
    read_only = bool(hints.get("readOnlyHint"))
    return {
        "read_only": read_only,
        "destructive": False,
        "idempotent": read_only,
        "open_world": bool(hints.get("openWorldHint", False)),
    }


async def call(home, name, arguments):
    """Runs tool [name] for [home] (the conversation user's view of this instance): its result, or
    `{"error": …}` for a refusal, which goes back to the model as text so it can correct itself."""
    try:
        if name == "home_overview":
            return home.overview()
        if name not in MCP_TOOL_NAMES:
            return {"error": f"Unknown tool {name}"}
        return await mcp.call_tool(home, name, arguments if isinstance(arguments, dict) else {})
    except mcp.ToolError as failure:
        return {"error": str(failure)}


def schema_fields(input_schema):
    """[input_schema]'s properties as `(name, python type, required, description)`, for the
    voluptuous schema an LLM API tool declares (`llm_api.py`): string, integer, number, boolean and
    object properties, as the MCP tools use."""
    types = {"string": str, "integer": int, "number": float, "boolean": bool, "object": dict}
    required = set(input_schema.get("required") or [])
    fields = []
    for name, prop in (input_schema.get("properties") or {}).items():
        kind = prop.get("type")
        if kind not in types:
            raise ValueError(f"{name}: a {kind} property has no LLM API type")
        fields.append((name, types[kind], name in required, prop.get("description")))
    return fields
