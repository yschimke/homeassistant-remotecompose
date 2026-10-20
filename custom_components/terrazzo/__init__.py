"""Serve the local Terrazzo renderer; all data access stays with HA's user session."""
from pathlib import Path

import voluptuous as vol
from homeassistant.components import frontend, panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.config_entries import SOURCE_IMPORT
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import issue_registry as ir

from .const import (
    CONF_DASHBOARD,
    CONF_TITLE,
    DEFAULT_TITLE,
    DOMAIN,
    PANEL_URL_PATH,
    clean_options,
)

CONFIG_SCHEMA = vol.Schema(
    {
        DOMAIN: vol.Schema(
            {
                vol.Optional(CONF_DASHBOARD, default=""): cv.string,
                vol.Optional(CONF_TITLE, default=DEFAULT_TITLE): cv.string,
            }
        )
    },
    extra=vol.ALLOW_EXTRA,
)


async def async_setup(hass, config):
    """Carry a `terrazzo:` YAML block into the config entry, which owns the panel."""
    if DOMAIN in config:
        options = clean_options(config[DOMAIN])
        if entries := hass.config_entries.async_entries(DOMAIN):
            # HA refuses new flows once the single entry exists, so update it in place. This runs
            # before the entry is set up, which then reads these options.
            if dict(entries[0].options) != options:
                hass.config_entries.async_update_entry(entries[0], options=options)
        else:
            hass.async_create_task(
                hass.config_entries.flow.async_init(
                    DOMAIN, context={"source": SOURCE_IMPORT}, data=options
                )
            )
        # YAML is re-imported on every start, so it would undo edits made in the UI.
        ir.async_create_issue(
            hass,
            DOMAIN,
            "yaml_overrides_ui",
            is_fixable=False,
            severity=ir.IssueSeverity.WARNING,
            translation_key="yaml_overrides_ui",
        )
    else:
        ir.async_delete_issue(hass, DOMAIN, "yaml_overrides_ui")
    return True


async def async_setup_entry(hass, entry):
    """Register static assets and an authenticated HA frontend panel at /terrazzo."""
    state = hass.data.setdefault(DOMAIN, {})
    # Static routes can't be removed, so a reloaded entry keeps the first registration.
    if not state.get("static"):
        await hass.http.async_register_static_paths(
            [StaticPathConfig("/terrazzo-static", str(Path(__file__).parent / "frontend"), False)]
        )
        state["static"] = True
    options = entry.options
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path=PANEL_URL_PATH,
        webcomponent_name="terrazzo-panel",
        sidebar_title=options.get(CONF_TITLE) or DEFAULT_TITLE,
        sidebar_icon="mdi:view-dashboard",
        module_url="/terrazzo-static/terrazzo-panel.js",
        config={"dashboard": options.get(CONF_DASHBOARD, "")},
        require_admin=False,
    )
    entry.async_on_unload(entry.add_update_listener(_async_options_updated))
    return True


async def async_unload_entry(hass, entry):
    """Take the panel out of the sidebar; the static code stays served until restart."""
    frontend.async_remove_panel(hass, PANEL_URL_PATH)
    return True


async def _async_options_updated(hass, entry):
    await hass.config_entries.async_reload(entry.entry_id)
