"""Serve the local Terrazzo renderer; all data access stays with HA's user session."""
from pathlib import Path

import voluptuous as vol
from homeassistant.components import panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.helpers import config_validation as cv

DOMAIN = "terrazzo"
CONFIG_SCHEMA = vol.Schema(
    {
        DOMAIN: vol.Schema(
            {
                vol.Optional("dashboard", default=""): cv.string,
                vol.Optional("title", default="Terrazzo"): cv.string,
            }
        )
    },
    extra=vol.ALLOW_EXTRA,
)


async def async_setup(hass, config):
    """Register static assets and an authenticated HA frontend panel at /terrazzo."""
    options = config[DOMAIN]
    await hass.http.async_register_static_paths(
        [StaticPathConfig("/terrazzo-static", str(Path(__file__).parent / "frontend"), False)]
    )
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path="terrazzo",
        webcomponent_name="terrazzo-panel",
        sidebar_title=options["title"],
        sidebar_icon="mdi:view-dashboard",
        module_url="/terrazzo-static/terrazzo-panel.js",
        config={"dashboard": options["dashboard"]},
        require_admin=False,
    )
    return True
