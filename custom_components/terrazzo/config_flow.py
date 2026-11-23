"""Set Terrazzo up from Settings → Devices & services; YAML is imported into the same entry."""
import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.helpers.selector import (
    ConversationAgentSelector,
    SelectOptionDict,
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
    TextSelector,
)

from .const import (
    CONF_AGENT,
    CONF_DASHBOARD,
    CONF_DEFAULT_DASHBOARD,
    CONF_MCP_USER,
    CONF_SIDEBAR_DASHBOARDS,
    CONF_TITLE,
    CONF_TOP_LEVEL_DASHBOARDS,
    DEFAULT_DASHBOARD_NONE,
    DEFAULT_TITLE,
    DOMAIN,
    MCP_OFF,
    TOP_LEVEL_HIDDEN,
    TOP_LEVEL_OFF,
    TOP_LEVEL_SELECTED,
    clean_options,
)


def _lovelace_dashboards(hass):
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")
    return dashboards or {}


def _dashboard_options(hass, include_lovelace=False, annotate_hidden=False):
    """Lovelace dashboards by URL path. Lovelace's data shape differs across HA releases."""
    dashboards = _lovelace_dashboards(hass)
    options = []
    seen = set()
    for url_path, dashboard in dashboards.items():
        if not url_path:
            continue
        if url_path == "lovelace" and not include_lovelace:
            continue  # The default Overview; the panel's picker offers it when it can draw it.
        if url_path in seen:
            continue
        seen.add(url_path)
        config = getattr(dashboard, "config", None) or {}
        title = config.get("title") if isinstance(config, dict) else None
        hidden = isinstance(config, dict) and config.get("show_in_sidebar") is False
        base = f"{title} ({url_path})" if title else url_path
        label = f"{base} — hidden in Lovelace" if annotate_hidden and hidden else base
        options.append(SelectOptionDict(value=url_path, label=label))
    return sorted(options, key=lambda option: option["label"].casefold())


async def _mcp_user_options(hass):
    """Off, then every person who can sign in: the one user the MCP server may serve."""
    users = [user for user in await hass.auth.async_get_users() if user.is_active and not user.system_generated]
    return [SelectOptionDict(value=MCP_OFF, label="Off")] + sorted(
        (
            SelectOptionDict(value=user.id, label=f"{user.name} (administrator)" if user.is_admin else user.name)
            for user in users
        ),
        key=lambda option: option["label"].casefold(),
    )


def _top_level_mode_options():
    return [
        SelectOptionDict(value=TOP_LEVEL_OFF, label="Off (single Terrazzo link)"),
        SelectOptionDict(
            value=TOP_LEVEL_HIDDEN,
            label="Hidden Lovelace dashboards (show hidden dashboards in sidebar as Terrazzo)",
        ),
        SelectOptionDict(
            value=TOP_LEVEL_SELECTED,
            label="Selected dashboards (choose below)",
        ),
    ]


def _schema(hass, options, mcp_users=None):
    """Title, optional first dashboard, and top-level sidebar dashboard promotion settings. The
    options form adds the MCP server's user ([mcp_users]) and conversation agent."""
    dashboard = options.get(CONF_DASHBOARD) or ""
    top_level_mode = options.get(CONF_TOP_LEVEL_DASHBOARDS) or TOP_LEVEL_OFF
    sidebar_dashboards = list(options.get(CONF_SIDEBAR_DASHBOARDS) or [])
    default_dashboard = options.get(CONF_DEFAULT_DASHBOARD) or DEFAULT_DASHBOARD_NONE
    top_level_choices = _dashboard_options(hass, include_lovelace=True, annotate_hidden=True)
    default_choices = [
        SelectOptionDict(value=DEFAULT_DASHBOARD_NONE, label="Keep Home Assistant's default")
    ] + top_level_choices
    fields = {
        vol.Required(CONF_TITLE, default=options.get(CONF_TITLE) or DEFAULT_TITLE): TextSelector(),
        vol.Optional(
            CONF_DASHBOARD, description={"suggested_value": dashboard} if dashboard else None
        ): SelectSelector(
            SelectSelectorConfig(
                options=_dashboard_options(hass),
                custom_value=True,
                mode=SelectSelectorMode.DROPDOWN,
            )
        ),
        vol.Optional(CONF_TOP_LEVEL_DASHBOARDS, default=top_level_mode): SelectSelector(
            SelectSelectorConfig(
                options=_top_level_mode_options(),
                mode=SelectSelectorMode.DROPDOWN,
            )
        ),
        vol.Optional(CONF_SIDEBAR_DASHBOARDS, default=sidebar_dashboards): SelectSelector(
            SelectSelectorConfig(
                options=top_level_choices,
                multiple=True,
                custom_value=True,
                mode=SelectSelectorMode.DROPDOWN,
            )
        ),
        vol.Optional(CONF_DEFAULT_DASHBOARD, default=default_dashboard): SelectSelector(
            SelectSelectorConfig(
                options=default_choices,
                custom_value=True,
                mode=SelectSelectorMode.DROPDOWN,
            )
        ),
    }
    if mcp_users is not None:
        current = options.get(CONF_MCP_USER) or MCP_OFF
        if current not in {option["value"] for option in mcp_users}:
            current = MCP_OFF  # The chosen user was deleted or deactivated.
        fields[vol.Required(CONF_MCP_USER, default=current)] = SelectSelector(
            SelectSelectorConfig(options=mcp_users, mode=SelectSelectorMode.DROPDOWN)
        )
        agent = options.get(CONF_AGENT) or ""
        # The conversation agent that answers edit sessions: any of Home Assistant's, with the
        # "Terrazzo dashboards" API on in its own options (dashboard_agent.py). Empty for none.
        fields[vol.Optional(CONF_AGENT, description={"suggested_value": agent} if agent else None)] = (
            ConversationAgentSelector()
        )
    return vol.Schema(fields)


class TerrazzoConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """One entry: the /terrazzo panel."""

    VERSION = 1
    # 1.2: Home Assistant's conversation agents answer edit sessions; the built-in agent's
    # provider, model, URL and API key are gone (`async_migrate_entry`).
    MINOR_VERSION = 2

    async def async_step_user(self, user_input=None):
        if self._async_current_entries():
            return self.async_abort(reason="single_instance_allowed")
        if user_input is not None:
            return self.async_create_entry(title=DEFAULT_TITLE, data={}, options=clean_options(user_input))
        return self.async_show_form(step_id="user", data_schema=_schema(self.hass, {}))

    async def async_step_import(self, import_data):
        """A first start with a `terrazzo:` YAML block; later starts update the entry in setup."""
        if self._async_current_entries():
            return self.async_abort(reason="already_configured")
        return self.async_create_entry(title=DEFAULT_TITLE, data={}, options=clean_options(import_data))

    @staticmethod
    @callback
    def async_get_options_flow(config_entry):
        return TerrazzoOptionsFlow(config_entry)


class TerrazzoOptionsFlow(config_entries.OptionsFlow):
    """Change the sidebar title, the first dashboard, the MCP server's user and the conversation
    agent that answers edit sessions; the panel reloads in place."""

    def __init__(self, config_entry):
        # Not `self.config_entry`: newer HA sets that itself and warns when a flow assigns it.
        self._entry = config_entry

    async def async_step_init(self, user_input=None):
        if user_input is not None:
            return self.async_create_entry(title="", data=clean_options(user_input))
        return self.async_show_form(
            step_id="init",
            data_schema=_schema(self.hass, self._entry.options, await _mcp_user_options(self.hass)),
        )
