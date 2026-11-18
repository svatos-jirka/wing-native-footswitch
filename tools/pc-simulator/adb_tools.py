"""Locate adb.exe without hard-coded user paths."""

from __future__ import annotations

import os
import shutil
from pathlib import Path


def find_adb() -> str:
    """adb from PATH, ANDROID_HOME / ANDROID_SDK_ROOT, or the default Android Studio SDK folder."""
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        roots.append(str(Path(local_app_data) / "Android" / "Sdk"))
    for root in roots:
        if root:
            candidate = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if candidate.is_file():
                return str(candidate)
    return "adb"
