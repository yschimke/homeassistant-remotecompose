"""Serve the local Terrazzo renderer; all data access stays with HA's user session.

The optional MCP server (`mcp_view.py`) is the one exception: off by default, and when on it
serves exactly one chosen HA user of this instance, with that user's own tokens and permissions.
Dashboard edit sessions (`edits.py`) hold an agent's drafts until a person applies one with their
own session. The app's notifications, while it holds no websocket, are held here too (`push.py`):
the app is woken with a content-free FCM message and collects them with its own session.
"""
from pathlib import Path

import voluptuous as vol
from homeassistant.components import frontend, panel_custom
from homeassistant.components.http import StaticPathConfig
from homeassistant.config_entries import SOURCE_IMPORT
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers.collection import ItemNotFound
from homeassistant.loader import async_get_integration

from .const import (
    CARD_URL,
    CONF_DASHBOARD,
    CONF_MCP_USER,
    CONF_TITLE,
    DATA_CARD_RESOURCE,
    DEFAULT_TITLE,
    DOMAIN,
    PANEL_URL_PATH,
    STATIC_URL,
    clean_options,
)
from . import edits, push
from .mcp_view import TerrazzoMcpMetadataView, TerrazzoMcpView

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
            # The MCP server's user is chosen in the UI only; YAML leaves it as it was.
            options[CONF_MCP_USER] = entries[0].options.get(CONF_MCP_USER, "")
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
            [StaticPathConfig(STATIC_URL, str(Path(__file__).parent / "frontend"), False)]
        )
        state["static"] = True
    # Likewise the MCP views; each request checks the loaded entry's options, so while no user is
    # chosen (or the entry is unloaded) they answer 404.
    if not state.get("mcp"):
        hass.http.register_view(TerrazzoMcpView(hass))
        hass.http.register_view(TerrazzoMcpMetadataView(hass))
        state["mcp"] = True
    # Edit sessions outlive a reload of the entry; their commands are registered once, like the views.
    if "edits" not in state:
        state["edits"] = await edits.Edits.async_load(hass)
        edits.async_register(hass)
    # Held notifications for the app's silent push, likewise (push.py).
    if "push" not in state:
        state["push"] = await push.Push.async_load(hass)
        push.async_register(hass)
    options = entry.options
    # Static files are served without Cache-Control, so a browser may keep an old release's panel
    # and renderer for hours after an update. Each release gets its own URLs instead.
    version = (await async_get_integration(hass, DOMAIN)).version
    await panel_custom.async_register_panel(
        hass,
        frontend_url_path=PANEL_URL_PATH,
        webcomponent_name="terrazzo-panel",
        sidebar_title=options.get(CONF_TITLE) or DEFAULT_TITLE,
        sidebar_icon="mdi:view-dashboard",
        module_url=f"{STATIC_URL}/terrazzo-panel.js?v={version}",
        config={"dashboard": options.get(CONF_DASHBOARD, ""), "version": str(version)},
        require_admin=False,
    )
    # Before the update listener: recording the resource in the entry must not reload it.
    await _async_add_card_resource(hass, entry)
    entry.async_on_unload(entry.add_update_listener(_async_options_updated))
    return True


async def async_unload_entry(hass, entry):
    """Take the panel out of the sidebar; the static code stays served until restart."""
    frontend.async_remove_panel(hass, PANEL_URL_PATH)
    return True


async def async_remove_entry(hass, entry):
    """Delete the card resource if Terrazzo added it and it is still there."""
    if (resource_id := entry.data.get(DATA_CARD_RESOURCE)) and (
        resources := await _async_storage_resources(hass)
    ):
        try:
            await resources.async_delete_item(resource_id)
        except ItemNotFound:
            pass


async def _async_storage_resources(hass):
    """Lovelace's resource collection when it is editable (storage mode), else None."""
    lovelace = hass.data.get("lovelace")
    if isinstance(lovelace, dict):  # Before LovelaceData, HA kept a plain dict here.
        mode, resources = lovelace.get("resource_mode"), lovelace.get("resources")
    else:
        mode, resources = getattr(lovelace, "resource_mode", None), getattr(lovelace, "resources", None)
    if mode != "storage" or resources is None:
        return None
    if not resources.loaded:
        await resources.async_load()
        resources.loaded = True
    return resources


async def _async_add_card_resource(hass, entry):
    """Add the Remote Compose card as a Lovelace resource, once.

    A real resource rather than `frontend.add_extra_js_url`: Lovelace loads resources after HA's
    scoped custom-element registry is in place, where an extra module can define the element too
    early and lose it (home-assistant/frontend#53890), and Cast receivers load only resources.
    It is added once: a user who deletes it keeps it deleted. In YAML mode the resource list is
    the user's to edit, so the docs give the line to add.
    """
    if DATA_CARD_RESOURCE in entry.data or (resources := await _async_storage_resources(hass)) is None:
        return
    if any(item.get("url", "").split("?")[0] == CARD_URL for item in resources.async_items()):
        resource_id = None  # The user's own resource; never delete it.
    else:
        resource_id = (await resources.async_create_item({"res_type": "module", "url": CARD_URL}))["id"]
    hass.config_entries.async_update_entry(entry, data={**entry.data, DATA_CARD_RESOURCE: resource_id})


async def _async_options_updated(hass, entry):
    await hass.config_entries.async_reload(entry.entry_id)
