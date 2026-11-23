"""Top-level Terrazzo sidebar panels: promotion, live sync, hub hiding, and default_panel."""

import asyncio
from dataclasses import dataclass
import importlib.util
from pathlib import Path
import sys
import types
import unittest

ROOT = Path(__file__).resolve().parents[2] / "custom_components/terrazzo"

_pkg = types.ModuleType("_terrazzo_panels_pkg")
_pkg.__path__ = [str(ROOT)]
sys.modules[_pkg.__name__] = _pkg


def _load(name):
    spec = importlib.util.spec_from_file_location(f"{_pkg.__name__}.{name}", ROOT / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


const = _load("const")
panels_mod = _load("panels")


@dataclass
class FakePanel:
    component_name: str
    sidebar_title: str | None
    sidebar_icon: str | None
    frontend_url_path: str
    config: dict | None
    require_admin: bool
    show_in_sidebar: bool


class FakeDashboard:
    def __init__(self, config, loaded=None, error=None):
        self.config = config
        self._loaded = loaded
        self._error = error

    async def async_load(self, force=False):
        if self._error is not None:
            raise self._error
        return self._loaded


class FakeSystemStore:
    def __init__(self, data=None):
        self.data = dict(data or {})
        self.saves = []

    async def async_set_item(self, key, value):
        self.data[key] = dict(value)
        self.saves.append((key, dict(value)))


class FakeFrontend:
    DATA_PANELS = "frontend_panels"
    DATA_PANELS_CONFIG = "frontend_panels_config"

    def __init__(self, system_store=None):
        self.registrations = []
        self.removals = []
        self.system_store = system_store or FakeSystemStore()

    def async_register_built_in_panel(
        self,
        hass,
        component_name,
        sidebar_title=None,
        sidebar_icon=None,
        frontend_url_path=None,
        config=None,
        require_admin=False,
        *,
        update=False,
        show_in_sidebar=True,
    ):
        panels = hass.data.setdefault(self.DATA_PANELS, {})
        if not update and frontend_url_path in panels:
            raise ValueError(f"Overwriting panel {frontend_url_path}")
        panel = FakePanel(
            component_name=component_name,
            sidebar_title=sidebar_title,
            sidebar_icon=sidebar_icon,
            frontend_url_path=frontend_url_path,
            config=config,
            require_admin=require_admin,
            show_in_sidebar=show_in_sidebar,
        )
        panels[frontend_url_path] = panel
        self.registrations.append((frontend_url_path, update, show_in_sidebar, sidebar_title))

    def async_remove_panel(self, hass, frontend_url_path, *, warn_if_unknown=True):
        removed = hass.data.get(self.DATA_PANELS, {}).pop(frontend_url_path, None)
        if removed is not None:
            self.removals.append(frontend_url_path)

    async def async_system_store(self, hass):
        return self.system_store


class FakeHass:
    def __init__(self, dashboards=None, panels=None, panels_config=None):
        self.data = {
            "lovelace": types.SimpleNamespace(dashboards=dict(dashboards or {})),
            FakeFrontend.DATA_PANELS: dict(panels or {}),
            FakeFrontend.DATA_PANELS_CONFIG: dict(panels_config or {}),
        }


def _lovelace_panel(url_path, title, icon="mdi:view-dashboard", show_in_sidebar=True, require_admin=False):
    return FakePanel(
        component_name="lovelace",
        sidebar_title=title,
        sidebar_icon=icon,
        frontend_url_path=url_path,
        config={"mode": "storage"},
        require_admin=require_admin,
        show_in_sidebar=show_in_sidebar,
    )


class PanelsTest(unittest.TestCase):
    def test_clean_options_normalizes_top_level_fields(self):
        cleaned = const.clean_options(
            {
                const.CONF_TOP_LEVEL_DASHBOARDS: "bogus",
                const.CONF_SIDEBAR_DASHBOARDS: [" kitchen ", "", "kitchen", "living"],
                const.CONF_DEFAULT_DASHBOARD: const.DEFAULT_DASHBOARD_NONE,
            }
        )
        self.assertEqual(cleaned[const.CONF_TOP_LEVEL_DASHBOARDS], const.TOP_LEVEL_OFF)
        self.assertEqual(cleaned[const.CONF_SIDEBAR_DASHBOARDS], ["kitchen", "living"])
        self.assertEqual(cleaned[const.CONF_DEFAULT_DASHBOARD], "")

        selected = const.clean_options(
            {
                const.CONF_TOP_LEVEL_DASHBOARDS: const.TOP_LEVEL_HIDDEN,
                const.CONF_DEFAULT_DASHBOARD: " kitchen ",
            }
        )
        self.assertEqual(selected[const.CONF_TOP_LEVEL_DASHBOARDS], const.TOP_LEVEL_HIDDEN)
        self.assertEqual(selected[const.CONF_DEFAULT_DASHBOARD], "kitchen")

    def test_is_renderable_dashboard_matches_explicit_views_contract(self):
        self.assertTrue(panels_mod.is_renderable_dashboard({"views": [{"title": "Home", "cards": []}]}))
        self.assertTrue(panels_mod.is_renderable_dashboard({"views": []}))
        self.assertFalse(panels_mod.is_renderable_dashboard({"strategy": {"type": "map"}}))
        self.assertFalse(
            panels_mod.is_renderable_dashboard({"views": [{"title": "Auto", "strategy": {"type": "areas"}}]})
        )
        self.assertFalse(panels_mod.is_renderable_dashboard(None))

    def test_off_mode_keeps_terrazzo_hub_in_sidebar_and_promotes_nothing(self):
        async def run():
            hass = FakeHass(
                dashboards={
                    "kitchen": FakeDashboard(
                        {"title": "Kitchen", "show_in_sidebar": False},
                        loaded={"views": [{"cards": []}]},
                    )
                },
                panels={"kitchen": _lovelace_panel("kitchen", "Kitchen", show_in_sidebar=False)},
            )
            fe = FakeFrontend()
            manager = panels_mod.TerrazzoPanelManager(hass, fe)
            await manager.async_configure(const.clean_options({}), "0.1.102")
            panels = hass.data[FakeFrontend.DATA_PANELS]
            self.assertIn("terrazzo", panels)
            self.assertTrue(panels["terrazzo"].show_in_sidebar)
            self.assertNotIn("terrazzo-kitchen", panels)

        asyncio.run(run())

    def test_hidden_mode_promotes_only_hidden_renderable_dashboards_and_hides_hub(self):
        async def run():
            hass = FakeHass(
                dashboards={
                    "kitchen": FakeDashboard(
                        {"title": "Kitchen", "icon": "mdi:silverware", "show_in_sidebar": False, "require_admin": True},
                        loaded={"views": [{"title": "Main", "cards": []}]},
                    ),
                    "visible-home": FakeDashboard(
                        {"title": "Visible Home", "show_in_sidebar": True},
                        loaded={"views": [{"cards": []}]},
                    ),
                    "map": FakeDashboard(
                        {"title": "Map", "show_in_sidebar": False},
                        loaded={"strategy": {"type": "map"}},
                    ),
                    "broken": FakeDashboard(
                        {"title": "Broken", "show_in_sidebar": False},
                        error=RuntimeError("ConfigNotFound"),
                    ),
                },
                panels={
                    "kitchen": _lovelace_panel("kitchen", "Kitchen", "mdi:silverware", show_in_sidebar=False),
                    "visible-home": _lovelace_panel("visible-home", "Visible Home", show_in_sidebar=True),
                },
            )
            fe = FakeFrontend()
            manager = panels_mod.TerrazzoPanelManager(hass, fe)
            await manager.async_configure(
                const.clean_options({const.CONF_TOP_LEVEL_DASHBOARDS: const.TOP_LEVEL_HIDDEN}),
                "0.1.102",
            )
            panels = hass.data[FakeFrontend.DATA_PANELS]
            # Native Lovelace panels stay untouched.
            self.assertEqual(panels["kitchen"].component_name, "lovelace")
            self.assertFalse(panels["kitchen"].show_in_sidebar)
            # Promoted top-level Terrazzo panel appears in sidebar with copied metadata.
            promoted = panels["terrazzo-kitchen"]
            self.assertEqual(promoted.component_name, "custom")
            self.assertTrue(promoted.show_in_sidebar)
            self.assertEqual(promoted.sidebar_title, "Kitchen")
            self.assertEqual(promoted.sidebar_icon, "mdi:silverware")
            self.assertTrue(promoted.require_admin)
            self.assertEqual(promoted.config["dashboard"], "kitchen")
            self.assertTrue(promoted.config["pinned_dashboard"])
            self.assertTrue(promoted.config["terrazzo_managed"])
            # Non-hidden, strategy, and unreadable dashboards are skipped.
            self.assertNotIn("terrazzo-visible-home", panels)
            self.assertNotIn("terrazzo-map", panels)
            self.assertNotIn("terrazzo-broken", panels)
            # Hub stays routable at /terrazzo but hidden from sidebar.
            self.assertIn("terrazzo", panels)
            self.assertFalse(panels["terrazzo"].show_in_sidebar)

            # Second sync with no changes must be a complete no-op (prevents EVENT_PANELS_UPDATED loops).
            before_regs = len(fe.registrations)
            before_rems = len(fe.removals)
            await manager.async_sync()
            self.assertEqual(len(fe.registrations), before_regs)
            self.assertEqual(len(fe.removals), before_rems)

            # Live sync: renaming and re-showing in Lovelace updates/removes Terrazzo panel and restores hub.
            hass.data["lovelace"].dashboards["kitchen"].config["title"] = "Chef Kitchen"
            await manager.async_sync()
            self.assertEqual(panels["terrazzo-kitchen"].sidebar_title, "Chef Kitchen")

            hass.data["lovelace"].dashboards["kitchen"].config["show_in_sidebar"] = True
            await manager.async_sync()
            self.assertNotIn("terrazzo-kitchen", panels)
            self.assertTrue(panels["terrazzo"].show_in_sidebar)

        asyncio.run(run())

    def test_selected_mode_and_panel_overrides_and_collision_protection(self):
        async def run():
            foreign = FakePanel("custom", "Foreign", "mdi:star", "terrazzo-foreign", {"other": True}, False, True)
            hass = FakeHass(
                dashboards={
                    "wall-panel": FakeDashboard(
                        {"title": "Wall", "show_in_sidebar": True},
                        loaded={"views": [{"cards": []}]},
                    ),
                    "foreign": FakeDashboard(
                        {"title": "Foreign", "show_in_sidebar": False},
                        loaded={"views": [{"cards": []}]},
                    ),
                },
                panels={"terrazzo-foreign": foreign},
                panels_config={"wall-panel": {"title": "Hallway Wall", "icon": "mdi:tablet"}},
            )
            fe = FakeFrontend()
            manager = panels_mod.TerrazzoPanelManager(hass, fe)
            await manager.async_configure(
                const.clean_options(
                    {
                        const.CONF_TOP_LEVEL_DASHBOARDS: const.TOP_LEVEL_SELECTED,
                        const.CONF_SIDEBAR_DASHBOARDS: ["wall-panel", "foreign"],
                    }
                ),
                "0.1.102",
            )
            panels = hass.data[FakeFrontend.DATA_PANELS]
            self.assertEqual(panels["terrazzo-wall-panel"].sidebar_title, "Hallway Wall")
            self.assertEqual(panels["terrazzo-wall-panel"].sidebar_icon, "mdi:tablet")
            # Non-Terrazzo panel with colliding url_path must never be overwritten.
            self.assertIs(panels["terrazzo-foreign"], foreign)

        asyncio.run(run())

    def test_default_dashboard_sets_and_restores_system_default_panel(self):
        async def run():
            store = FakeSystemStore({"core": {"default_panel": "home"}})
            fe = FakeFrontend(system_store=store)
            hass = FakeHass(
                dashboards={
                    "kitchen": FakeDashboard(
                        {"title": "Kitchen", "show_in_sidebar": False},
                        loaded={"views": [{"cards": []}]},
                    ),
                },
            )
            manager = panels_mod.TerrazzoPanelManager(hass, fe)
            await manager.async_configure(
                const.clean_options(
                    {
                        const.CONF_TOP_LEVEL_DASHBOARDS: const.TOP_LEVEL_HIDDEN,
                        const.CONF_DEFAULT_DASHBOARD: "kitchen",
                    }
                ),
                "0.1.102",
            )
            self.assertEqual(store.data["core"]["default_panel"], "terrazzo-kitchen")

            # Unloading removes promoted panels and restores the prior default_panel.
            await manager.async_unload()
            self.assertNotIn("terrazzo-kitchen", hass.data[FakeFrontend.DATA_PANELS])
            self.assertNotIn("terrazzo", hass.data[FakeFrontend.DATA_PANELS])
            self.assertEqual(store.data["core"]["default_panel"], "home")

        asyncio.run(run())


if __name__ == "__main__":
    unittest.main()
