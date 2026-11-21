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
# Options: the built-in dashboard agent (assistant.py). Off while the provider is "off" or no API
# key is set; with it on, every edit session is answered by an LLM that Home Assistant calls itself.
CONF_AGENT_PROVIDER = "agent_provider"
CONF_AGENT_API_KEY = "agent_api_key"
CONF_AGENT_MODEL = "agent_model"
CONF_AGENT_BASE_URL = "agent_base_url"
AGENT_OFF = "off"
MCP_URL = "/api/terrazzo/mcp"
# RFC 9728's well-known location for MCP_URL's protected-resource metadata.
MCP_METADATA_URL = f"/.well-known/oauth-protected-resource{MCP_URL}"


AGENT_OPTIONS = (CONF_AGENT_PROVIDER, CONF_AGENT_API_KEY, CONF_AGENT_MODEL, CONF_AGENT_BASE_URL)


def clean_options(values, previous=None):
    """The entry's options from a form or YAML: a title, a dashboard that may be empty, the MCP
    server's user (empty for off; YAML never sets it, see `async_setup`) and the built-in agent.

    The agent's API key is never shown back in the form, so a blank key keeps [previous]'s while
    the agent stays on; turning the agent off forgets it."""
    previous = previous or {}
    mcp_user = (values.get(CONF_MCP_USER) or "").strip()
    provider = (values.get(CONF_AGENT_PROVIDER) or "").strip() or AGENT_OFF
    key = (values.get(CONF_AGENT_API_KEY) or "").strip()
    if provider == AGENT_OFF:
        key = ""
    elif not key:
        key = previous.get(CONF_AGENT_API_KEY, "")
    return {
        CONF_TITLE: (values.get(CONF_TITLE) or "").strip() or DEFAULT_TITLE,
        CONF_DASHBOARD: (values.get(CONF_DASHBOARD) or "").strip(),
        CONF_MCP_USER: "" if mcp_user == MCP_OFF else mcp_user,
        CONF_AGENT_PROVIDER: provider,
        CONF_AGENT_API_KEY: key,
        CONF_AGENT_MODEL: (values.get(CONF_AGENT_MODEL) or "").strip(),
        CONF_AGENT_BASE_URL: (values.get(CONF_AGENT_BASE_URL) or "").strip().rstrip("/"),
    }
