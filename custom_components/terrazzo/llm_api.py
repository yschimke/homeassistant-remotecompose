"""The dashboard tools as a Home Assistant LLM API ("Terrazzo dashboards"), so a conversation agent
the person already uses, or Home Assistant's MCP Server, can edit dashboards: see `llm_tools.py`.

Each tool runs as the conversation's own user (its context), through the same `HassHome` the MCP
server uses: that user's permissions decide what it reads, and only an administrator can apply a
draft, in Terrazzo. While the agent answers an edit session (`dashboard_agent.py`), each call is
also a step in that session's chat ([TURN]).
"""
import contextvars

import voluptuous as vol
from homeassistant.auth.permissions.const import POLICY_READ
from homeassistant.core import callback
from homeassistant.helpers import area_registry as ar
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers import llm

from . import llm_tools
from .mcp_view import HassHome

# What home_overview lists: enough to plan a dashboard, bounded for a large home.
OVERVIEW_PER_AREA = 60
OVERVIEW_UNASSIGNED = 80

# The edit session turn the current task runs, if any (`dashboard_agent.Turn`): set around the
# agent's `async_converse`, so the tools it calls, in that task or ones it starts, report to it.
TURN = contextvars.ContextVar("terrazzo_dashboard_turn", default=None)


class DashboardHome(HassHome):
    """The MCP server's home for one person, plus `home_overview`."""

    def overview(self):
        """This home's areas with their entities, and entity counts per domain: what the person
        could put on a dashboard."""
        hass = self._hass
        areas = ar.async_get(hass)
        devices = dr.async_get(hass)
        entities = er.async_get(hass)
        by_area, unassigned, domains = {}, [], {}
        for state in hass.states.async_all():
            entity_id = state.entity_id
            if not self._can(entity_id, POLICY_READ):
                continue
            domain = entity_id.split(".", 1)[0]
            domains[domain] = domains.get(domain, 0) + 1
            entry = entities.async_get(entity_id)
            if entry is not None and (entry.hidden_by or entry.entity_category):
                continue  # Diagnostic and hidden entities rarely belong on a dashboard.
            area_id = entry.area_id if entry is not None else None
            if area_id is None and entry is not None and entry.device_id:
                device = devices.async_get(entry.device_id)
                area_id = device.area_id if device is not None else None
            name = state.attributes.get("friendly_name") or entity_id
            row = f"{entity_id} ({name}): {state.state}"
            if area_id is None:
                unassigned.append(row)
            else:
                by_area.setdefault(area_id, []).append(row)
        listed = []
        for area_id, rows in by_area.items():
            area = areas.async_get_area(area_id)
            listed.append(
                {
                    "area": area.name if area is not None else area_id,
                    "entities": sorted(rows)[:OVERVIEW_PER_AREA],
                    "more": max(0, len(rows) - OVERVIEW_PER_AREA),
                }
            )
        return {
            "areas": sorted(listed, key=lambda a: a["area"].casefold()),
            "not_in_an_area": sorted(unassigned)[:OVERVIEW_UNASSIGNED],
            "not_in_an_area_more": max(0, len(unassigned) - OVERVIEW_UNASSIGNED),
            "entities_per_domain": dict(sorted(domains.items())),
        }


def base_url(hass):
    """This instance's URL for links in tool results (a session's `terrazzo_url`), else empty."""
    try:
        from homeassistant.helpers.network import NoURLAvailableError, get_url

        try:
            return get_url(hass, prefer_external=True)
        except NoURLAvailableError:
            return ""
    except ImportError:
        return ""


def _annotations(spec):
    """[spec]'s annotations, on a Home Assistant that has them (2026.11 and later)."""
    kind = getattr(llm, "ToolAnnotations", None)
    return kind(**llm_tools.annotations(spec)) if kind is not None else None


class _DashboardTool(llm.Tool):
    """One of `llm_tools.tool_specs()`, run by `llm_tools.call`."""

    integration = "terrazzo"

    def __init__(self, spec):
        self.name = spec["name"]
        self.description = spec["description"]
        self.parameters = vol.Schema(
            {
                (vol.Required if required else vol.Optional)(name, description=description): kind
                for name, kind, required, description in llm_tools.schema_fields(spec["inputSchema"])
            }
        )
        annotations = _annotations(spec)
        if annotations is not None:
            self.annotations = annotations

    async def async_call(self, hass, tool_input, llm_context):
        user_id = llm_context.context.user_id if llm_context.context else None
        user = await hass.auth.async_get_user(user_id) if user_id else None
        if user is None or not user.is_active:
            return {"error": "Terrazzo's dashboard tools need a signed-in Home Assistant user"}
        turn = TURN.get()
        arguments = dict(tool_input.tool_args or {})
        if turn is not None and turn.user_id == user.id:
            arguments = turn.prepare(self.name, arguments)
            turn.calling(self.name, arguments)
        result = await llm_tools.call(DashboardHome(hass, user, base_url(hass)), self.name, arguments)
        if turn is not None and turn.user_id == user.id:
            turn.called(self.name, arguments, result)
        return result


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
