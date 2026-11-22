"""Shared names for the Terrazzo integration."""

DOMAIN = "terrazzo"
PANEL_URL_PATH = "terrazzo"
CONF_DASHBOARD = "dashboard"
CONF_TITLE = "title"
DEFAULT_TITLE = "Terrazzo"
STATIC_URL = "/terrazzo-static"
CARD_URL = f"{STATIC_URL}/remote-compose-card.js"
# Entry data: the Lovelace resource id Terrazzo added for the card, or None for the user's own.
DATA_CARD_RESOURCE = "card_resource"
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
LEGACY_AGENT_OPTIONS = (CONF_AGENT,)


def clean_options(values):
    """The entry's options from a form or YAML: a title, a dashboard that may be empty, the MCP
    server's user (empty for off; YAML never sets it, see `async_setup`) and the conversation
    agent that answers edit sessions (empty for none)."""
    mcp_user = (values.get(CONF_MCP_USER) or "").strip()
    return {
        CONF_TITLE: (values.get(CONF_TITLE) or "").strip() or DEFAULT_TITLE,
        CONF_DASHBOARD: (values.get(CONF_DASHBOARD) or "").strip(),
        CONF_MCP_USER: "" if mcp_user == MCP_OFF else mcp_user,
        CONF_AGENT: (values.get(CONF_AGENT) or "").strip(),
    }
