from __future__ import annotations
from homeassistant.components.device_tracker import TrackerEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from .coordinator import CasaTrackCoordinator
from .entity import CasaTrackEntity

async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    coordinator: CasaTrackCoordinator = entry.runtime_data
    known: set[str] = set()

    def add_new() -> None:
        new_ids = [device_id for device_id in coordinator.data if device_id not in known]
        if new_ids:
            known.update(new_ids)
            async_add_entities([CasaTrackTracker(coordinator, device_id) for device_id in new_ids])

    add_new()
    entry.async_on_unload(coordinator.async_add_listener(add_new))

class CasaTrackTracker(CasaTrackEntity, TrackerEntity):
    _attr_name = None

    def __init__(self, coordinator: CasaTrackCoordinator, device_id: str) -> None:
        super().__init__(coordinator, device_id)
        self._attr_unique_id = f"{device_id}_location"

    @property
    def latitude(self): return self.row.get("latitude")
    @property
    def longitude(self): return self.row.get("longitude")
    @property
    def location_accuracy(self):
        value = self.row.get("accuracy_m")
        return int(round(value)) if value is not None else 0

    @property
    def extra_state_attributes(self):
        return {
            "activity": self.row.get("activity"),
            "activity_confidence": self.row.get("activity_confidence"),
            "location_source": self.row.get("location_source"),
            "wifi_ssid": self.row.get("wifi_ssid"),
            "server_time_ms": self.row.get("server_time_ms"),
        }
