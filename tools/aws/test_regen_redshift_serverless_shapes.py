"""Tests for regen_redshift_serverless_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import regen_redshift_serverless_shapes as r


MODEL = {
    "operations": {
        "GetTrack": {"input": {"shape": "GetTrackRequest"}, "output": {"shape": "GetTrackResponse"}},
        "ListTracks": {"input": {"shape": "ListTracksRequest"}},
        "NoInput": {},
    },
    "shapes": {
        "GetTrackRequest": {"type": "structure", "required": ["trackName"], "members": {
            "trackName": {"shape": "TrackName"},
            "tags": {"shape": "TagList"},
        }},
        "GetTrackResponse": {"type": "structure", "members": {"track": {"shape": "Marker"}}},
        "ListTracksRequest": {"type": "structure", "members": {"maxResults": {"shape": "MaxResults"}}},
        "TrackName": {"type": "string", "min": 1, "max": 256, "pattern": "^[a-zA-Z0-9_]+$"},
        "TagList": {"type": "list", "member": {"shape": "Tag"}, "min": 0, "max": 200},
        "Tag": {"type": "structure", "required": ["key", "value"], "members": {
            "key": {"shape": "TagKey"}, "value": {"shape": "TagValue"}}},
        "TagKey": {"type": "string", "min": 1, "max": 128},
        "TagValue": {"type": "string", "min": 0, "max": 256},
        "MaxResults": {"type": "integer", "min": 1, "max": 100},
        "Status": {"type": "string", "enum": ["a", "b"]},
        "Marker": {"type": "string"},
    },
}


def test_keeps_only_input_shapes_with_their_constraints():
    doc = r.build(MODEL, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_redshift_serverless_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"GetTrack": "GetTrackRequest", "ListTracks": "ListTracksRequest"}
    assert "GetTrackResponse" not in doc["shapes"]
    assert "Status" not in doc["shapes"]
    assert doc["shapes"]["GetTrackRequest"] == {
        "type": "structure", "required": ["trackName"],
        "members": {"trackName": "TrackName", "tags": "TagList"}}
    assert doc["shapes"]["TrackName"] == {"type": "string", "min": 1, "max": 256, "pattern": "^[a-zA-Z0-9_]+$"}
    assert doc["shapes"]["TagList"] == {"type": "list", "min": 0, "max": 200, "member": "Tag"}
    assert doc["shapes"]["MaxResults"] == {"type": "integer", "min": 1, "max": 100}


def test_enum_is_kept():
    model = {
        "operations": {"Op": {"input": {"shape": "OpRequest"}}},
        "shapes": {
            "OpRequest": {"type": "structure", "members": {"status": {"shape": "Status"}}},
            "Status": {"type": "string", "enum": ["a", "b"]},
        },
    }

    assert r.build(model, "botocore test")["shapes"]["Status"] == {"type": "string", "enum": ["a", "b"]}


def test_check_ignores_provenance(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    output.write_text(r.render(r.build(MODEL, "botocore 0.0.1 (local/aws/botocore)")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "--botocore-data /elsewhere"))
    monkeypatch.setattr(r, "load_model", lambda data: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 0


def test_check_reports_changed_constraints(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    changed = {**MODEL, "shapes": {**MODEL["shapes"], "MaxResults": {"type": "integer", "min": 1, "max": 50}}}
    output.write_text(r.render(r.build(changed, "botocore test")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 1


def test_output_is_stable():
    first = r.render(r.build(MODEL, "botocore test"))
    second = r.render(r.build(MODEL, "botocore test"))

    assert first == second
    assert first.endswith("\n")
