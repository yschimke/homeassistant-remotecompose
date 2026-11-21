"""Set Terrazzo up from Settings → Devices & services; YAML is imported into the same entry."""
import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.helpers.selector import (
    SelectOptionDict,
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from .assistant import PROVIDERS
from .const import (
    AGENT_OFF,
    CONF_AGENT_API_KEY,
    CONF_AGENT_BASE_URL,
    CONF_AGENT_MODEL,
    CONF_AGENT_PROVIDER,
    CONF_DASHBOARD,
    CONF_MCP_USER,
    CONF_TITLE,
    DEFAULT_TITLE,
    DOMAIN,
    MCP_OFF,
    clean_options,
)


def _dashboard_options(hass):
    """Lovelace dashboards by URL path. Lovelace's data shape differs across HA releases."""
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")
    options = []
    for url_path, dashboard in (dashboards or {}).items():
        if not url_path or url_path == "lovelace":
            continue  # The default Overview; the panel's picker offers it when it can draw it.
        config = getattr(dashboard, "config", None) or {}
        title = config.get("title") if isinstance(config, dict) else None
        options.append(SelectOptionDict(value=url_path, label=f"{title} ({url_path})" if title else url_path))
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


def _schema(hass, options, mcp_users=None):
    """Title and an optional first dashboard; leaving it empty opens the dashboard picker. The
    options form adds the MCP server's user ([mcp_users]); setup leaves the server off."""
    dashboard = options.get(CONF_DASHBOARD) or ""
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
    }
    if mcp_users is not None:
        current = options.get(CONF_MCP_USER) or MCP_OFF
        if current not in {option["value"] for option in mcp_users}:
            current = MCP_OFF  # The chosen user was deleted or deactivated.
        fields[vol.Required(CONF_MCP_USER, default=current)] = SelectSelector(
            SelectSelectorConfig(options=mcp_users, mode=SelectSelectorMode.DROPDOWN)
        )
        fields.update(_agent_fields(options))
    return vol.Schema(fields)


def _agent_fields(options):
    """The built-in agent: a provider, its API key (never shown back: blank keeps the saved one),
    and optionally a model and, for an OpenAI-compatible service, its base URL."""
    provider = options.get(CONF_AGENT_PROVIDER) or AGENT_OFF
    if provider != AGENT_OFF and provider not in PROVIDERS:
        provider = AGENT_OFF
    model = options.get(CONF_AGENT_MODEL) or ""
    base_url = options.get(CONF_AGENT_BASE_URL) or ""
    return {
        vol.Required(CONF_AGENT_PROVIDER, default=provider): SelectSelector(
            SelectSelectorConfig(
                options=[SelectOptionDict(value=AGENT_OFF, label="Off")]
                + [SelectOptionDict(value=key, label=p["label"]) for key, p in PROVIDERS.items()],
                mode=SelectSelectorMode.DROPDOWN,
            )
        ),
        vol.Optional(CONF_AGENT_API_KEY): TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD)),
        vol.Optional(CONF_AGENT_MODEL, description={"suggested_value": model} if model else None): TextSelector(),
        vol.Optional(
            CONF_AGENT_BASE_URL, description={"suggested_value": base_url} if base_url else None
        ): TextSelector(TextSelectorConfig(type=TextSelectorType.URL)),
    }


class TerrazzoConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """One entry: the /terrazzo panel."""

    VERSION = 1

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
    """Change the sidebar title, the first dashboard, the MCP server's user and the built-in
    agent; the panel reloads in place."""

    def __init__(self, config_entry):
        # Not `self.config_entry`: newer HA sets that itself and warns when a flow assigns it.
        self._entry = config_entry

    async def async_step_init(self, user_input=None):
        errors = {}
        if user_input is not None:
            options = clean_options(user_input, self._entry.options)
            provider = options[CONF_AGENT_PROVIDER]
            if provider != AGENT_OFF and not options[CONF_AGENT_API_KEY]:
                errors[CONF_AGENT_API_KEY] = "agent_key_required"
            elif provider != AGENT_OFF and PROVIDERS[provider].get("base_url") is None and not options[CONF_AGENT_BASE_URL]:
                errors[CONF_AGENT_BASE_URL] = "agent_base_url_required"
            elif provider != AGENT_OFF and PROVIDERS[provider].get("default_model") is None and not options[CONF_AGENT_MODEL]:
                errors[CONF_AGENT_MODEL] = "agent_model_required"
            elif options[CONF_AGENT_BASE_URL] and not options[CONF_AGENT_BASE_URL].startswith(("https://", "http://")):
                errors[CONF_AGENT_BASE_URL] = "agent_base_url_https"
            else:
                return self.async_create_entry(title="", data=options)
        return self.async_show_form(
            errors=errors,
            step_id="init",
            # A refused form comes back with what was typed, except the key, which is never shown.
            data_schema=_schema(
                self.hass,
                {**self._entry.options, **{k: v for k, v in (user_input or {}).items() if k != CONF_AGENT_API_KEY}},
                await _mcp_user_options(self.hass),
            ),
        )
