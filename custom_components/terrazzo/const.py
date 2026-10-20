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


def clean_options(values):
    """The entry's options from a form or YAML: a title, and a dashboard that may be empty."""
    return {
        CONF_TITLE: (values.get(CONF_TITLE) or "").strip() or DEFAULT_TITLE,
        CONF_DASHBOARD: (values.get(CONF_DASHBOARD) or "").strip(),
    }
