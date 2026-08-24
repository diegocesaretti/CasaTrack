from __future__ import annotations
from datetime import timedelta
from typing import Any
from aiohttp import ClientError
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed
from .const import CONF_SCAN_INTERVAL, CONF_TOKEN, CONF_URL, DEFAULT_SCAN_INTERVAL, DOMAIN

class CasaTrackCoordinator(DataUpdateCoordinator[dict[str, dict[str, Any]]]):
    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.url = entry.data[CONF_URL].rstrip("/")
        self.token = entry.data[CONF_TOKEN]
        self.session = async_get_clientsession(hass)
        super().__init__(
            hass,
            logger=__import__("logging").getLogger(__name__),
            name=DOMAIN,
            update_interval=timedelta(seconds=entry.options.get(CONF_SCAN_INTERVAL, entry.data.get(CONF_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL))),
        )

    async def _async_update_data(self) -> dict[str, dict[str, Any]]:
        try:
            async with self.session.get(
                f"{self.url}/v1/states",
                headers={"Authorization": f"Bearer {self.token}"},
                timeout=15,
            ) as response:
                if response.status in (401, 403):
                    raise UpdateFailed("CasaTrack authentication failed")
                response.raise_for_status()
                payload = await response.json()
        except (ClientError, TimeoutError, ValueError) as err:
            raise UpdateFailed(f"CasaTrack API error: {err}") from err
        return {d["device_id"]: d for d in payload.get("devices", []) if d.get("device_id")}
