"""Tests for regen_iam_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_shapes as r


MODEL = {
    "operations": {
        "GetPolicy": {"input": {"shape": "GetPolicyRequest"}, "output": {"shape": "GetPolicyResponse"}},
        "ListPolicies": {"input": {"shape": "ListPoliciesRequest"}},
    },
    "shapes": {
        "GetPolicyRequest": {"type": "structure", "required": ["PolicyArn"], "members": {
            "PolicyArn": {"shape": "arnType"},
        }},
        "GetPolicyResponse": {"type": "structure", "members": {"Policy": {"shape": "arnType"}}},
        "ListPoliciesRequest": {"type": "structure", "members": {"MaxItems": {"shape": "maxItemsType"}}},
        "arnType": {"type": "string", "min": 20, "max": 2048},
        "maxItemsType": {"type": "integer", "min": 1, "max": 1000},
    },
}


def test_keeps_only_the_listed_operations_with_their_constraints(monkeypatch):
    monkeypatch.setattr(r, "OPERATIONS", ("GetPolicy",))

    doc = r.build(MODEL, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_iam_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"GetPolicy": "GetPolicyRequest"}
    assert "GetPolicyResponse" not in doc["shapes"]
    assert "maxItemsType" not in doc["shapes"]
    assert doc["shapes"]["GetPolicyRequest"] == {
        "type": "structure", "required": ["PolicyArn"], "members": {"PolicyArn": "arnType"}}
    assert doc["shapes"]["arnType"] == {"type": "string", "min": 20, "max": 2048}


def test_a_misspelled_operation_is_refused_rather_than_skipped(monkeypatch):
    monkeypatch.setattr(r, "OPERATIONS", ("GetPolciy",))

    with pytest.raises(KeyError):
        r.build(MODEL, "botocore test")


def test_the_vendored_file_carries_every_listed_operation():
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))

    assert sorted(doc["operations"]) == sorted(r.OPERATIONS)
    for operation, shape in doc["operations"].items():
        assert shape in doc["shapes"], operation


def test_check_ignores_provenance(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "OPERATIONS", ("GetPolicy",))
    output = tmp_path / "shapes.json"
    output.write_text(r.render(r.build(MODEL, "botocore 0.0.1 (local/aws/botocore)")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "--botocore-data /elsewhere"))
    monkeypatch.setattr(r, "load_model", lambda data, model: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 0


def test_check_reports_a_changed_constraint(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "OPERATIONS", ("GetPolicy",))
    output = tmp_path / "shapes.json"
    changed = {**MODEL, "shapes": {**MODEL["shapes"], "arnType": {"type": "string", "min": 1, "max": 2048}}}
    output.write_text(r.render(r.build(changed, "botocore test")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model: MODEL)

    assert r.main(["--check", "--output", str(output)]) == 1


def test_output_is_stable(monkeypatch):
    monkeypatch.setattr(r, "OPERATIONS", ("GetPolicy", "ListPolicies"))

    first = r.render(r.build(MODEL, "botocore test"))
    second = r.render(r.build(MODEL, "botocore test"))

    assert first == second
    assert first.endswith("\n")
