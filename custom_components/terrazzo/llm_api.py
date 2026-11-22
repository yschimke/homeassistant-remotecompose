"""The dashboard tools as a Home Assistant LLM API ("Terrazzo dashboards"), so a conversation agent
the person already uses, or Home Assistant's MCP Server, can edit dashboards: see `llm_tools.py`.

Each tool runs as the conversation's own user (its context), through the same `HassHome` the MCP
server and the built-in assistant use: that user's permissions decide what it reads, and only an
administrator can apply a draft, in Terrazzo.
"""
import voluptuous as vol
from homeassistant.core import callback
from homeassistant.helpers import llm

from . import llm_tools
from .assistant_hass import AssistantHome, _base_url


class _DashboardTool(llm.Tool):
    """One of `llm_tools.tool_specs()`, run by `llm_tools.call`."""

    def __init__(self, spec):
        self.name = spec["name"]
        self.description = spec["description"]
        self.parameters = vol.Schema(
            {
                (vol.Required if required else vol.Optional)(name, description=description): kind
                for name, kind, required, description in llm_tools.schema_fields(spec["inputSchema"])
            }
        )

    async def async_call(self, hass, tool_input, llm_context):
        user_id = llm_context.context.user_id if llm_context.context else None
        user = await hass.auth.async_get_user(user_id) if user_id else None
        if user is None or not user.is_active:
            return {"error": "Terrazzo's dashboard tools need a signed-in Home Assistant user"}
        home = AssistantHome(hass, user, _base_url(hass))
        return await llm_tools.call(home, self.name, tool_input.tool_args)


class TerrazzoDashboardsApi(llm.API):
    """Terrazzo's dashboard editing tools, for any LLM integration's conversation agent."""

    def __init__(self, hass):
        super().__init__(hass=hass, id=llm_tools.API_ID, name=llm_tools.API_NAME)

    async def async_get_api_instance(self, llm_context):
        return llm.APIInstance(
            api=self,
            api_prompt=llm_tools.API_PROMPT,
            llm_context=llm_context,
            tools=[_DashboardTool(spec) for spec in llm_tools.tool_specs()],
        )


@callback
def async_register(hass):
    """Registers the API for this Home Assistant run; returns its unregister callback."""
    return llm.async_register_api(hass, TerrazzoDashboardsApi(hass))
