"""Fixes for Terrazzo's repairs: allowing a Terrazzo web install's origin (web_origins.py)."""
import voluptuous as vol
from homeassistant.components.repairs import RepairsFlow

from . import web_origins


class WebOriginFlow(RepairsFlow):
    """Confirm, then add the origin to HA's HTTP config and restart."""

    def __init__(self, origin):
        self.origin = origin

    async def async_step_init(self, user_input=None):
        return await self.async_step_confirm()

    async def async_step_confirm(self, user_input=None):
        if user_input is not None:
            if reason := await web_origins.async_allow(self.hass, self.origin):
                return self.async_abort(
                    reason=reason, description_placeholders={"origin": self.origin}
                )
            return self.async_create_entry(data={})
        return self.async_show_form(
            step_id="confirm",
            data_schema=vol.Schema({}),
            description_placeholders={"origin": self.origin},
        )


async def async_create_fix_flow(hass, issue_id, data):
    return WebOriginFlow(data["origin"])
