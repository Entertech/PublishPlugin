from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DistributionConfig:
    group_id: str
    artifact_id: str
    plugin_id: str
    version: str
    central_namespace: str

    @classmethod
    def load(cls, path: Path | str = "gradle.properties") -> "DistributionConfig":
        properties = _read_properties(Path(path))
        return cls(
            group_id=_required(properties, "publishGroup"),
            artifact_id=_required(properties, "publishArtifactId"),
            plugin_id=_required(properties, "publishPluginId"),
            version=_required(properties, "publishVersion"),
            central_namespace=_required(properties, "publishCentralNamespace"),
        )


def _read_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        separator = "=" if "=" in line else ":"
        if separator not in line:
            continue
        key, value = line.split(separator, 1)
        properties[key.strip()] = value.strip()
    return properties


def _required(properties: dict[str, str], key: str) -> str:
    value = properties.get(key, "").strip()
    if not value:
        raise ValueError(f"Missing required distribution property: {key}")
    return value
