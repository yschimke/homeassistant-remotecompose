"""Local WASM dashboard panel and Remote Compose Lovelace card assets."""
from pathlib import Path

from homeassistant.components import frontend, panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.core import HomeAssistant
from homeassistant.helpers.typing import ConfigType

DOMAIN = "terrazzo"
STATIC = "/terrazzo_static"


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Serve only packaged static assets; HA owns authentication and all data APIs."""
    options = config.get(DOMAIN) or {}
    root = Path(__file__).parent / "frontend"
    await hass.http.async_register_static_paths(
        [StaticPathConfig(STATIC, str(root), False)]
    )
    frontend.add_extra_js_url(hass, f"{STATIC}/terrazzo.js", es5=False)
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path="terrazzo",
        webcomponent_name="terrazzo-panel",
        sidebar_title="Terrazzo",
        sidebar_icon="mdi:view-dashboard",
        module_url=f"{STATIC}/terrazzo.js",
        config={"dashboard": options.get("dashboard")},
        require_admin=False,
    )
    return True
