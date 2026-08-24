from __future__ import annotations
from datetime import datetime, timezone
from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE, UnitOfSpeed
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from .coordinator import CasaTrackCoordinator
from .entity import CasaTrackEntity

async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    c: CasaTrackCoordinator = entry.runtime_data
    known: set[str] = set()

    def add_new() -> None:
        new_ids = [device_id for device_id in c.data if device_id not in known]
        entities = []
        for device_id in new_ids:
            entities += [
                CasaTrackValueSensor(c, device_id, "activity", "Actividad", None, None),
                CasaTrackValueSensor(c, device_id, "battery_pct", "Batería", SensorDeviceClass.BATTERY, PERCENTAGE),
                CasaTrackSpeedSensor(c, device_id),
                CasaTrackLastSeenSensor(c, device_id),
                CasaTrackValueSensor(c, device_id, "location_source", "Fuente de ubicación", None, None),
            ]
        if new_ids:
            known.update(new_ids)
            async_add_entities(entities)

    add_new()
    entry.async_on_unload(c.async_add_listener(add_new))

class CasaTrackValueSensor(CasaTrackEntity, SensorEntity):
    def __init__(self, c, device_id, key, name, device_class, unit):
        super().__init__(c, device_id)
        self.key = key
        self._attr_name = name
        self._attr_unique_id = f"{device_id}_{key}"
        self._attr_device_class = device_class
        self._attr_native_unit_of_measurement = unit
    @property
    def native_value(self): return self.row.get(self.key)

class CasaTrackSpeedSensor(CasaTrackEntity, SensorEntity):
    _attr_name = "Velocidad"
    _attr_native_unit_of_measurement = UnitOfSpeed.KILOMETERS_PER_HOUR
    _attr_icon = "mdi:speedometer"
    def __init__(self, c, device_id):
        super().__init__(c, device_id); self._attr_unique_id = f"{device_id}_speed"
    @property
    def native_value(self):
        v = self.row.get("speed_mps")
        return round(float(v) * 3.6, 1) if v is not None else None

class CasaTrackLastSeenSensor(CasaTrackEntity, SensorEntity):
    _attr_name = "Última actualización"
    _attr_device_class = SensorDeviceClass.TIMESTAMP
    def __init__(self, c, device_id):
        super().__init__(c, device_id); self._attr_unique_id = f"{device_id}_last_seen"
    @property
    def native_value(self):
        v = self.row.get("server_time_ms")
        return datetime.fromtimestamp(float(v)/1000, tz=timezone.utc) if v else None
