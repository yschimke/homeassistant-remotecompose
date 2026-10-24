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
MCP_URL = "/api/terrazzo/mcp"
# RFC 9728's well-known location for MCP_URL's protected-resource metadata.
MCP_METADATA_URL = f"/.well-known/oauth-protected-resource{MCP_URL}"


def clean_options(values):
    """The entry's options from a form or YAML: a title, a dashboard that may be empty, and the
    MCP server's user (empty for off; YAML never sets it, see `async_setup`)."""
    mcp_user = (values.get(CONF_MCP_USER) or "").strip()
    return {
        CONF_TITLE: (values.get(CONF_TITLE) or "").strip() or DEFAULT_TITLE,
        CONF_DASHBOARD: (values.get(CONF_DASHBOARD) or "").strip(),
        CONF_MCP_USER: "" if mcp_user == MCP_OFF else mcp_user,
    }
