#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for the action capture queue (drain semantics, no device needed)."""

import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def _fresh_home():
    os.environ["EVOLIA_HOME"] = tempfile.mkdtemp()


def test_enqueue_then_drain_roundtrip():
    _fresh_home()
    import evolia_actions as actions

    actions.enqueue("photo_taken")
    actions.enqueue("sms_sent", count=2)
    actions.enqueue("screen_input", count=5)

    events = actions.drain()
    kinds = {e["kind"]: e["count"] for e in events}
    assert kinds == {"photo_taken": 1, "sms_sent": 2, "screen_input": 5}

    # Queue is emptied after draining.
    assert actions.drain() == []


def test_unknown_kind_rejected():
    _fresh_home()
    import evolia_actions as actions

    try:
        actions.enqueue("mining")
    except ValueError:
        pass
    else:
        raise AssertionError("expected ValueError for unknown action")


def test_events_between_drains_are_preserved():
    _fresh_home()
    import evolia_actions as actions

    actions.enqueue("video_taken")
    assert [e["kind"] for e in actions.drain()] == ["video_taken"]
    actions.enqueue("photo_taken")
    assert [e["kind"] for e in actions.drain()] == ["photo_taken"]


def test_run_loop_consumes_queue_into_value():
    _fresh_home()
    import evolia_actions as actions
    from evolia_sensors import SensorSample
    from evolia_value import EvoliaValue

    actions.enqueue("video_taken")
    actions.enqueue("photo_taken", count=2)

    value = EvoliaValue()
    for event in actions.drain():
        value.record_action(event["kind"], event["count"])
    summary = value.cycle(SensorSample())

    # base = 8.0 (video) + 2*2.5 (photos) = 13.0
    assert summary["base_btc_e"] == 13.0
    assert value.action_counts["video_taken"] == 1
    assert value.action_counts["photo_taken"] == 2


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc}")
    sys.exit(1 if failures else 0)
