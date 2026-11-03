"""Local content-addressed file store. Replaced by the bank file service later (keeps the same interface)."""

from __future__ import annotations

import hashlib
from pathlib import Path


class FileStore:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def put(self, data: bytes, extension: str) -> tuple[str, str]:
        """Stores bytes once per content hash. Returns (sha256, relative path)."""
        digest = hashlib.sha256(data).hexdigest()
        rel = f"{digest[:2]}/{digest}{extension}"
        target = self.root / rel
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        return digest, rel

    def path(self, rel: str) -> Path:
        resolved = (self.root / rel).resolve()
        if self.root.resolve() not in resolved.parents:
            raise ValueError("path outside store")
        return resolved
