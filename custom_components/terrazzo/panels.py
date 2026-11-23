"""Top-level Terrazzo sidebar panels for Lovelace dashboards.

When enabled, Lovelace dashboards hidden from Home Assistant's sidebar (`show_in_sidebar: false`)
or explicitly selected in Terrazzo's options are exposed as top-level Terrazzo sidebar panels at
`/terrazzo-<url_path>`, copying each dashboard's title, icon, and admin requirement while leaving
Lovelace's own panels untouched at `/<url_path>` for native editing. While at least one top-level
dashboard panel is active, the generic `/terrazzo` hub stays routable for bookmarks but is hidden
from the sidebar.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from .const import (
    CONF_DASHBOARD,
    CONF_DEFAULT_DASHBOARD,
    CONF_SIDEBAR_DASHBOARDS,
    CONF_TITLE,
    CONF_TOP_LEVEL_DASHBOARDS,
    DEFAULT_TITLE,
    PANEL_PREFIX,
    PANEL_URL_PATH,
    STATIC_URL,
    TOP_LEVEL_HIDDEN,
    TOP_LEVEL_OFF,
    TOP_LEVEL_SELECTED,
)

_LOGGER = logging.getLogger(__name__)
DEFAULT_DASHBOARD_ICON = "mdi:view-dashboard"


def panel_url_path(dashboard_url_path: str | None) -> str:
    """The top-level Terrazzo frontend panel URL path for a Lovelace dashboard."""
    slug = "lovelace" if dashboard_url_path in (None, "", "lovelace") else str(dashboard_url_path).strip()
    return f"{PANEL_PREFIX}{slug}"


def normalize_dashboard_key(url_path: str | None) -> str:
    """Canonical key for matching configured dashboard URL paths (Overview is `'lovelace'`)."""
    if url_path in (None, "", "lovelace"):
        return "lovelace"
    return str(url_path).strip()


def is_renderable_dashboard(config: Any) -> bool:
    """Match ha-client's `hasRenderableViews`: explicit views without dashboard or view strategies."""
    if not isinstance(config, dict) or "strategy" in config:
        return False
    views = config.get("views")
    if not isinstance(views, list):
        return False
    return all(isinstance(view, dict) and "strategy" not in view for view in views)


def lovelace_dashboards(hass: Any) -> dict[str | None, Any]:
    """Lovelace's dashboards mapping across Home Assistant releases."""
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")
    return dashboards if isinstance(dashboards, dict) else {}


def _frontend_panels(hass: Any, frontend_mod: Any) -> dict[str, Any]:
    key = getattr(frontend_mod, "DATA_PANELS", "frontend_panels")
    panels = hass.data.get(key)
    if panels is None and key != "frontend_panels":
        panels = hass.data.get("frontend_panels")
    return panels if isinstance(panels, dict) else {}


def _frontend_panels_config(hass: Any, frontend_mod: Any) -> dict[str, Any]:
    key = getattr(frontend_mod, "DATA_PANELS_CONFIG", "frontend_panels_config")
    cfg = hass.data.get(key)
    if cfg is None and key != "frontend_panels_config":
        cfg = hass.data.get("frontend_panels_config")
    return cfg if isinstance(cfg, dict) else {}


def _is_terrazzo_managed(panel: Any) -> bool:
    config = getattr(panel, "config", None)
    return isinstance(config, dict) and bool(config.get("terrazzo_managed"))


def _dashboard_metadata(
    url_path: str,
    dashboard: Any,
    panels: dict[str, Any],
    panels_config: dict[str, Any],
) -> dict[str, Any]:
    """Effective sidebar metadata for a Lovelace dashboard, honoring frontend panel overrides."""
    raw = getattr(dashboard, "config", None)
    cfg = raw if isinstance(raw, dict) else {}
    panel_key = normalize_dashboard_key(url_path)
    existing_panel = panels.get(panel_key)
    override = panels_config.get(panel_key)
    override = override if isinstance(override, dict) else {}

    if "show_in_sidebar" in override:
        show_in_sidebar = bool(override["show_in_sidebar"])
    elif "show_in_sidebar" in cfg:
        show_in_sidebar = bool(cfg["show_in_sidebar"])
    elif existing_panel is not None and hasattr(existing_panel, "show_in_sidebar"):
        show_in_sidebar = bool(existing_panel.show_in_sidebar)
    else:
        show_in_sidebar = True

    title = (
        override.get("title")
        or cfg.get("title")
        or (getattr(existing_panel, "sidebar_title", None) if existing_panel is not None else None)
        or ("Overview" if panel_key == "lovelace" else panel_key)
    )
    icon = (
        override.get("icon")
        or cfg.get("icon")
        or (getattr(existing_panel, "sidebar_icon", None) if existing_panel is not None else None)
        or DEFAULT_DASHBOARD_ICON
    )
    if "require_admin" in override:
        require_admin = bool(override["require_admin"])
    elif "require_admin" in cfg:
        require_admin = bool(cfg["require_admin"])
    elif existing_panel is not None and hasattr(existing_panel, "require_admin"):
        require_admin = bool(existing_panel.require_admin)
    else:
        require_admin = False

    return {
        "dashboard": "" if panel_key == "lovelace" else panel_key,
        "key": panel_key,
        "title": str(title),
        "icon": str(icon),
        "require_admin": require_admin,
        "show_in_sidebar": show_in_sidebar,
    }


def _iter_lovelace_dashboards(hass: Any) -> list[tuple[str, Any]]:
    """Deduplicated `(canonical_url_path, dashboard)` pairs from Lovelace."""
    dashboards = lovelace_dashboards(hass)
    has_named_lovelace = "lovelace" in dashboards
    items: list[tuple[str, Any]] = []
    seen: set[str] = set()
    for raw_path, dashboard in dashboards.items():
        if raw_path is None and has_named_lovelace:
            continue
        key = normalize_dashboard_key(raw_path)
        if key in seen:
            continue
        seen.add(key)
        items.append((key, dashboard))
    return items


async def _is_dashboard_renderable(dashboard: Any) -> bool:
    loader = getattr(dashboard, "async_load", None)
    if loader is None:
        return False
    try:
        loaded = await loader(False)
    except Exception:  # ConfigNotFound / HomeAssistantError / unreadable YAML
        return False
    return is_renderable_dashboard(loaded)


async def async_desired_promoted_panels(
    hass: Any,
    options: dict[str, Any],
    frontend_mod: Any = None,
) -> dict[str, dict[str, Any]]:
    """Return `{terrazzo_url_path: spec}` for dashboards promoted to top-level sidebar panels."""
    mode = options.get(CONF_TOP_LEVEL_DASHBOARDS, TOP_LEVEL_OFF)
    if mode == TOP_LEVEL_OFF:
        return {}

    selected = {
        normalize_dashboard_key(item)
        for item in (options.get(CONF_SIDEBAR_DASHBOARDS) or ())
        if str(item or "").strip()
    }
    if mode == TOP_LEVEL_SELECTED and not selected:
        return {}

    panels = _frontend_panels(hass, frontend_mod)
    panels_config = _frontend_panels_config(hass, frontend_mod)
    desired: dict[str, dict[str, Any]] = {}

    for key, dashboard in _iter_lovelace_dashboards(hass):
        meta = _dashboard_metadata(key, dashboard, panels, panels_config)
        if mode == TOP_LEVEL_HIDDEN:
            if meta["show_in_sidebar"]:
                continue
        elif mode == TOP_LEVEL_SELECTED:
            if key not in selected:
                continue
        else:
            continue

        if not await _is_dashboard_renderable(dashboard):
            continue

        target_path = panel_url_path(key)
        desired[target_path] = {
            "dashboard": meta["dashboard"],
            "key": key,
            "title": meta["title"],
            "icon": meta["icon"],
            "require_admin": meta["require_admin"],
        }

    return desired


def _custom_panel_config(version: str, extra: dict[str, Any]) -> dict[str, Any]:
    return {
        **extra,
        "version": str(version),
        "terrazzo_managed": True,
        "_panel_custom": {
            "name": "terrazzo-panel",
            "module_url": f"{STATIC_URL}/terrazzo-panel.js?v={version}",
            "embed_iframe": False,
            "trust_external": False,
            "handle_safe_area": False,
        },
    }


def _register_terrazzo_panel(
    hass: Any,
    frontend_mod: Any,
    *,
    url_path: str,
    title: str,
    icon: str,
    require_admin: bool,
    show_in_sidebar: bool,
    config: dict[str, Any],
    update: bool,
) -> None:
    frontend_mod.async_register_built_in_panel(
        hass,
        component_name="custom",
        sidebar_title=title,
        sidebar_icon=icon,
        frontend_url_path=url_path,
        config=config,
        require_admin=require_admin,
        show_in_sidebar=show_in_sidebar,
        update=update,
    )


class TerrazzoPanelManager:
    """Keeps `/terrazzo` and promoted `/terrazzo-<slug>` sidebar panels in sync with Lovelace."""

    def __init__(self, hass: Any, frontend_mod: Any = None) -> None:
        self.hass = hass
        self._frontend = frontend_mod
        self._lock = asyncio.Lock()
        self._hub_spec: dict[str, Any] | None = None
        self._promoted_specs: dict[str, dict[str, Any]] = {}
        self._options: dict[str, Any] = {}
        self._version: str = ""
        self._active: bool = False
        self._previous_default_panel: str | None = None
        self._managed_default_panel: str | None = None

    def _get_frontend(self) -> Any:
        if self._frontend is None:
            from homeassistant.components import frontend

            self._frontend = frontend
        return self._frontend

    async def async_configure(self, options: dict[str, Any], version: str) -> None:
        """Apply new integration options and sync all panels immediately."""
        self._options = dict(options)
        self._version = str(version)
        self._active = True
        await self.async_sync()

    async def async_sync(self) -> None:
        """Idempotently reconcile registered Terrazzo panels against Lovelace's current state."""
        if not self._active:
            return
        async with self._lock:
            if not self._active:
                return
            frontend_mod = self._get_frontend()
            panels = _frontend_panels(self.hass, frontend_mod)
            desired_promoted = await async_desired_promoted_panels(
                self.hass, self._options, frontend_mod
            )

            # Remove promoted panels that are no longer desired.
            for url_path in list(self._promoted_specs):
                if url_path not in desired_promoted:
                    if url_path in panels and _is_terrazzo_managed(panels[url_path]):
                        frontend_mod.async_remove_panel(
                            self.hass, url_path, warn_if_unknown=False
                        )
                    self._promoted_specs.pop(url_path, None)

            # Register or update each desired promoted panel.
            active_promoted: dict[str, dict[str, Any]] = {}
            for url_path, spec in desired_promoted.items():
                existing = panels.get(url_path)
                if existing is not None and not _is_terrazzo_managed(existing):
                    _LOGGER.warning(
                        "Skipping top-level Terrazzo panel %s: already owned by %s",
                        url_path,
                        getattr(existing, "component_name", "unknown"),
                    )
                    continue
                desired_entry = {**spec, "version": self._version}
                active_promoted[url_path] = desired_entry
                if (
                    existing is not None
                    and self._promoted_specs.get(url_path) == desired_entry
                ):
                    continue
                _register_terrazzo_panel(
                    self.hass,
                    frontend_mod,
                    url_path=url_path,
                    title=spec["title"],
                    icon=spec["icon"],
                    require_admin=spec["require_admin"],
                    show_in_sidebar=True,
                    config=_custom_panel_config(
                        self._version,
                        {
                            "dashboard": spec["dashboard"],
                            "pinned_dashboard": True,
                        },
                    ),
                    update=existing is not None,
                )
            self._promoted_specs = active_promoted

            # Hide `/terrazzo` from the sidebar once at least one top-level dashboard is active;
            # keep it routable for direct URLs and wall-panel bookmarks.
            show_hub = len(self._promoted_specs) == 0
            desired_hub = {
                "title": self._options.get(CONF_TITLE) or DEFAULT_TITLE,
                "dashboard": self._options.get(CONF_DASHBOARD, ""),
                "show_in_sidebar": show_hub,
                "version": self._version,
            }
            existing_hub = panels.get(PANEL_URL_PATH)
            if existing_hub is None or self._hub_spec != desired_hub:
                _register_terrazzo_panel(
                    self.hass,
                    frontend_mod,
                    url_path=PANEL_URL_PATH,
                    title=desired_hub["title"],
                    icon=DEFAULT_DASHBOARD_ICON,
                    require_admin=False,
                    show_in_sidebar=show_hub,
                    config=_custom_panel_config(
                        self._version,
                        {"dashboard": desired_hub["dashboard"]},
                    ),
                    update=existing_hub is not None,
                )
                self._hub_spec = desired_hub

            await self._async_sync_default_panel(frontend_mod)

    async def _async_sync_default_panel(self, frontend_mod: Any) -> None:
        """Apply or restore Home Assistant's system `default_panel` when configured."""
        system_store_fn = getattr(frontend_mod, "async_system_store", None)
        if system_store_fn is None:
            return
        configured = (self._options.get(CONF_DEFAULT_DASHBOARD) or "").strip()
        target_panel = panel_url_path(configured) if configured else None
        if target_panel is not None and target_panel not in self._promoted_specs:
            target_panel = None

        if target_panel is None and self._managed_default_panel is None:
            # Check if a prior session left default_panel pointing to a removed Terrazzo panel.
            try:
                system_store = await system_store_fn(self.hass)
            except Exception:
                return
            core = (system_store.data or {}).get("core") or {}
            current = core.get("default_panel")
            if (
                isinstance(current, str)
                and current.startswith(PANEL_PREFIX)
                and current not in self._promoted_specs
            ):
                updated = {k: v for k, v in core.items() if k != "default_panel"}
                await system_store.async_set_item("core", updated)
            return

        try:
            system_store = await system_store_fn(self.hass)
        except Exception:
            return
        core = dict((system_store.data or {}).get("core") or {})
        current = core.get("default_panel")

        if target_panel is not None:
            if current != target_panel:
                if self._managed_default_panel is None and not (
                    isinstance(current, str) and current.startswith(PANEL_PREFIX)
                ):
                    self._previous_default_panel = current
                core["default_panel"] = target_panel
                await system_store.async_set_item("core", core)
            self._managed_default_panel = target_panel
        else:
            # Restore only if HA is still using the Terrazzo panel we set.
            if current == self._managed_default_panel:
                if self._previous_default_panel:
                    core["default_panel"] = self._previous_default_panel
                else:
                    core.pop("default_panel", None)
                await system_store.async_set_item("core", core)
            self._managed_default_panel = None

    async def async_unload(self) -> None:
        """Remove `/terrazzo` and all promoted `/terrazzo-*` panels and restore default_panel."""
        async with self._lock:
            self._active = False
            frontend_mod = self._get_frontend()
            panels = _frontend_panels(self.hass, frontend_mod)
            for url_path in list(self._promoted_specs):
                if url_path in panels and _is_terrazzo_managed(panels[url_path]):
                    frontend_mod.async_remove_panel(
                        self.hass, url_path, warn_if_unknown=False
                    )
            self._promoted_specs.clear()
            if PANEL_URL_PATH in panels:
                frontend_mod.async_remove_panel(
                    self.hass, PANEL_URL_PATH, warn_if_unknown=False
                )
            self._hub_spec = None

            # Restore default_panel if it still points to a Terrazzo panel we managed.
            system_store_fn = getattr(frontend_mod, "async_system_store", None)
            if system_store_fn is not None and self._managed_default_panel is not None:
                try:
                    system_store = await system_store_fn(self.hass)
                    core = dict((system_store.data or {}).get("core") or {})
                    if core.get("default_panel") == self._managed_default_panel:
                        if self._previous_default_panel:
                            core["default_panel"] = self._previous_default_panel
                        else:
                            core.pop("default_panel", None)
                        await system_store.async_set_item("core", core)
                except Exception:
                    pass
                self._managed_default_panel = None
