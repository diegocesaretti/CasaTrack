from __future__ import annotations
import voluptuous as vol
from homeassistant import config_entries
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from .const import CONF_SCAN_INTERVAL, CONF_TOKEN, CONF_URL, DEFAULT_SCAN_INTERVAL, DOMAIN

class CasaTrackConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input=None):
        errors = {}
        if user_input is not None:
            url = user_input[CONF_URL].rstrip("/")
            token = user_input[CONF_TOKEN]
            try:
                session = async_get_clientsession(self.hass)
                async with session.get(f"{url}/v1/states", headers={"Authorization": f"Bearer {token}"}, timeout=15) as resp:
                    if resp.status in (401, 403):
                        errors["base"] = "invalid_auth"
                    elif resp.status >= 400:
                        errors["base"] = "cannot_connect"
                    else:
                        await resp.json()
                        await self.async_set_unique_id(url)
                        self._abort_if_unique_id_configured()
                        return self.async_create_entry(title="CasaTrack", data={
                            CONF_URL: url,
                            CONF_TOKEN: token,
                            CONF_SCAN_INTERVAL: user_input[CONF_SCAN_INTERVAL],
                        })
            except Exception:
                errors["base"] = "cannot_connect"
        schema = vol.Schema({
            vol.Required(CONF_URL): str,
            vol.Required(CONF_TOKEN): str,
            vol.Optional(CONF_SCAN_INTERVAL, default=DEFAULT_SCAN_INTERVAL): vol.All(vol.Coerce(int), vol.Range(min=15, max=3600)),
        })
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)
