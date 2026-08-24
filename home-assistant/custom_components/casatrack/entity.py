from __future__ import annotations
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity
from .const import DOMAIN
from .coordinator import CasaTrackCoordinator

class CasaTrackEntity(CoordinatorEntity[CasaTrackCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: CasaTrackCoordinator, device_id: str) -> None:
        super().__init__(coordinator)
        self.device_id = device_id

    @property
    def row(self):
        return self.coordinator.data.get(self.device_id, {})

    @property
    def device_info(self) -> DeviceInfo:
        row = self.row
        return DeviceInfo(
            identifiers={(DOMAIN, self.device_id)},
            name=row.get("person_name") or row.get("label") or self.device_id,
            manufacturer="CasaTrack",
            model=row.get("label") or "Android tracker",
        )
