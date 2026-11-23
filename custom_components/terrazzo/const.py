"""Shared names for the Terrazzo integration."""

DOMAIN = "terrazzo"
PANEL_URL_PATH = "terrazzo"
PANEL_PREFIX = "terrazzo-"
CONF_DASHBOARD = "dashboard"
CONF_TITLE = "title"
DEFAULT_TITLE = "Terrazzo"
STATIC_URL = "/terrazzo-static"
CARD_URL = f"{STATIC_URL}/remote-compose-card.js"
# Entry data: the Lovelace resource id Terrazzo added for the card, or None for the user's own.
DATA_CARD_RESOURCE = "card_resource"
# Entry data: persisted {managed, previous} default_panel ownership so restoration survives restarts.
DATA_DEFAULT_PANEL_STATE = "default_panel_state"
# Option: promote Lovelace dashboards to top-level Terrazzo sidebar panels (`/terrazzo-<slug>`)
# and hide the generic `/terrazzo` sidebar link while at least one dashboard is promoted.
CONF_TOP_LEVEL_DASHBOARDS = "top_level_dashboards"
TOP_LEVEL_OFF = "off"
TOP_LEVEL_HIDDEN = "hidden"
TOP_LEVEL_SELECTED = "selected"
TOP_LEVEL_MODES = (TOP_LEVEL_OFF, TOP_LEVEL_HIDDEN, TOP_LEVEL_SELECTED)
# Option: explicit list of Lovelace dashboard URL paths promoted when mode is TOP_LEVEL_SELECTED.
CONF_SIDEBAR_DASHBOARDS = "sidebar_dashboards"
# Option: optional promoted dashboard URL path to set as Home Assistant's system default panel.
CONF_DEFAULT_DASHBOARD = "default_dashboard"
DEFAULT_DASHBOARD_NONE = "none"
# Option: the id of the one Home Assistant user the MCP server serves; empty while it is off.
CONF_MCP_USER = "mcp_user"
# The options form's choice for "no user", since a selector can't offer an empty value.
MCP_OFF = "off"
# Option: the Home Assistant conversation agent (an entity id, `conversation.…`) that answers edit
# sessions (dashboard_agent.py); empty while none does. It reaches the dashboards through the
# "Terrazzo dashboards" LLM API (llm_api.py), which its own options must turn on.
CONF_AGENT = "agent"
# The built-in agent's options before Home Assistant's agents answered sessions: the migration to
# entry version 1.2 removes them, with the API key.
LEGACY_AGENT_OPTIONS = ("agent_provider", "agent_api_key", "agent_model", "agent_base_url")
MCP_URL = "/api/terrazzo/mcp"
# RFC 9728's well-known location for MCP_URL's protected-resource metadata.
MCP_METADATA_URL = f"/.well-known/oauth-protected-resource{MCP_URL}"


AGENT_OPTIONS = (CONF_AGENT,)


def _clean_dashboard_list(values):
    if isinstance(values, str):
        values = [values]
    seen = []
    for item in values or ():
        path = str(item or "").strip()
        if path and path not in seen:
            seen.append(path)
    return seen


def clean_options(values):
    """The entry's options from a form or YAML: a title, a dashboard that may be empty, top-level
    sidebar dashboard promotion settings, the MCP server's user (empty for off; YAML never sets it,
    see `async_setup`) and the conversation agent that answers edit sessions (empty for none)."""
    mcp_user = (values.get(CONF_MCP_USER) or "").strip()
    top_level = (values.get(CONF_TOP_LEVEL_DASHBOARDS) or TOP_LEVEL_OFF).strip()
    if top_level not in TOP_LEVEL_MODES:
        top_level = TOP_LEVEL_OFF
    default_dashboard = (values.get(CONF_DEFAULT_DASHBOARD) or "").strip()
    if default_dashboard == DEFAULT_DASHBOARD_NONE:
        default_dashboard = ""
    return {
        CONF_TITLE: (values.get(CONF_TITLE) or "").strip() or DEFAULT_TITLE,
        CONF_DASHBOARD: (values.get(CONF_DASHBOARD) or "").strip(),
        CONF_TOP_LEVEL_DASHBOARDS: top_level,
        CONF_SIDEBAR_DASHBOARDS: _clean_dashboard_list(values.get(CONF_SIDEBAR_DASHBOARDS)),
        CONF_DEFAULT_DASHBOARD: default_dashboard,
        CONF_MCP_USER: "" if mcp_user == MCP_OFF else mcp_user,
        CONF_AGENT: (values.get(CONF_AGENT) or "").strip(),
    }
