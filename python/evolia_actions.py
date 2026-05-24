#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Digital-action capture for evolIA.

Detectors observe the device and APPEND events to an append-only queue file;
they never touch the value state. The single owner of the value state
(evolia_run.py) drains the queue each cycle. This keeps exactly one writer of
the state and makes capture race-free.

Detectable on a non-rooted device via termux-api:
  - sms_sent     : new sent SMS (termux-sms-list)
  - photo_taken  : new image files in the camera folder
  - video_taken  : new video files in the camera folder

Not passively observable without root/accessibility:
  - screen_input : fed manually / by Termux:Tasker shortcuts via the CLI:
                     python3 evolia_actions.py record screen_input [count]

Usage:
  python3 evolia_actions.py                      # run the detector daemon
  python3 evolia_actions.py record <kind> [n]    # enqueue n events of <kind>
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import evolia_paths as paths
from evolia_evolve import ACTION_RATES

PHOTO_EXTS = {".jpg", ".jpeg", ".png", ".heic", ".webp"}
VIDEO_EXTS = {".mp4", ".mov", ".3gp", ".mkv", ".webm"}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _have(cmd: str) -> bool:
    return shutil.which(cmd) is not None


def _run_json(args: list[str], timeout: float = 8.0):
    try:
        out = subprocess.check_output(args, timeout=timeout, stderr=subprocess.DEVNULL)
        text = out.decode("utf-8", "replace").strip()
        return json.loads(text) if text else None
    except Exception:
        return None


def queue_path() -> Path:
    return paths.evolia_home() / "evolia_action_queue.jsonl"


def enqueue(kind: str, count: int = 1) -> None:
    """Append an action event to the queue (used by detectors and the CLI)."""
    if kind not in ACTION_RATES:
        raise ValueError(f"unknown action: {kind!r} (expected {list(ACTION_RATES)})")
    if count <= 0:
        return
    paths.ensure_home()
    line = json.dumps({"kind": kind, "count": int(count), "ts": _now()})
    with open(queue_path(), "a") as f:
        f.write(line + "\n")


def drain() -> list[dict]:
    """Atomically take all queued events. New events go to a fresh file."""
    q = queue_path()
    if not q.exists():
        return []
    tmp = q.with_suffix(".draining")
    try:
        os.replace(q, tmp)  # atomic on the same filesystem
    except OSError:
        return []

    events: list[dict] = []
    for line in tmp.read_text().splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("kind") in ACTION_RATES:
            events.append({"kind": ev["kind"], "count": int(ev.get("count", 1))})
    tmp.unlink(missing_ok=True)
    return events


class SmsWatcher:
    """Enqueue sms_sent for each new sent message seen after start."""

    def __init__(self):
        self.seen = self._ids()

    def _ids(self) -> set[str]:
        data = _run_json(["termux-sms-list", "-t", "sent", "-l", "50"]) or []
        return {str(m.get("_id")) for m in data if isinstance(m, dict) and m.get("_id") is not None}

    def poll(self) -> int:
        if not _have("termux-sms-list"):
            return 0
        current = self._ids()
        new = current - self.seen
        self.seen |= current
        for _ in new:
            enqueue("sms_sent")
        return len(new)


class MediaWatcher:
    """Enqueue photo_taken / video_taken for new files in the camera folder."""

    def __init__(self, root: Path | None = None):
        self.root = root or Path(
            os.environ.get("EVOLIA_DCIM", os.path.expanduser("~/storage/dcim"))
        )
        self.seen = self._scan()

    def _scan(self) -> set[str]:
        files = set()
        if self.root.exists():
            for p in self.root.rglob("*"):
                if p.is_file():
                    files.add(str(p))
        return files

    def poll(self) -> int:
        current = self._scan()
        new = current - self.seen
        self.seen |= current
        n = 0
        for path in new:
            ext = Path(path).suffix.lower()
            if ext in PHOTO_EXTS:
                enqueue("photo_taken")
                n += 1
            elif ext in VIDEO_EXTS:
                enqueue("video_taken")
                n += 1
        return n


def run_daemon(interval: float = 5.0) -> None:
    sms = SmsWatcher()
    media = MediaWatcher()
    print(
        f"[actions] daemon start (interval={interval}s, sms={_have('termux-sms-list')}, "
        f"dcim={media.root})",
        flush=True,
    )
    try:
        while True:
            captured = sms.poll() + media.poll()
            if captured:
                print(f"[actions] queued {captured} event(s)", flush=True)
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[actions] daemon stopped", flush=True)


def main(argv: list[str]) -> int:
    if len(argv) >= 2 and argv[1] == "record":
        kind = argv[2] if len(argv) > 2 else ""
        count = int(argv[3]) if len(argv) > 3 else 1
        enqueue(kind, count)
        print(f"[actions] recorded {count} x {kind}")
        return 0
    run_daemon(float(os.environ.get("EVOLIA_CYCLE_SECONDS", "5")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
