"""Tests for regen_cfn_property_types.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import regen_cfn_property_types as r

SCHEMA = {
    "typeName": "AWS::Test::Thing",
    "definitions": {
        "Target": {"type": "object", "required": ["Id"], "properties": {
            "Id": {"type": "string"},
            "Retry": {"$ref": "#/definitions/Retry"},
            "Labels": {"type": "array", "items": {"type": "string"}},
            "Headers": {"type": "object", "patternProperties": {"^.+$": {"type": "string"}}},
            "Body": {"type": ["string", "object"]},
            "Children": {"type": "array", "items": {"$ref": "#/definitions/Target"}},
        }},
        "Retry": {"type": "object", "properties": {
            "Attempts": {"type": "integer"}, "Enabled": {"type": "boolean"}, "Ratio": {"type": "number"}}},
    },
    "properties": {
        "Name": {"type": "string"},
        "Targets": {"type": "array", "items": {"$ref": "#/definitions/Target"}},
        "Pattern": {"type": "object"},
        "Tags": {"type": "array", "items": {"type": "object", "properties": {"Key": {"type": "string"}}}},
    },
    "required": ["Name"],
}


def test_the_archive_names_a_schema_after_its_type():
    assert r.file_name("AWS::Events::Rule") == "aws-events-rule.json"
    assert r.file_name("AWS::Lambda::LayerVersion") == "aws-lambda-layerversion.json"


def test_each_schema_type_becomes_a_shape(monkeypatch):
    monkeypatch.setattr(r, "RESOURCE_TYPES", ["AWS::Test::Thing"])

    doc = r.build({"AWS::Test::Thing": SCHEMA}, "schemas test")

    assert doc["_source"]["schemas"] == "schemas test"
    assert doc["operations"] == {"AWS::Test::Thing": "AWS::Test::Thing"}
    shapes = doc["shapes"]
    assert shapes["AWS::Test::Thing"] == {"type": "structure", "members": {
        "Name": "String", "Targets": "AWS::Test::Thing.Targets", "Pattern": "Json", "Tags": "AWS::Test::Thing.Tags"},
        "required": ["Name"]}
    assert shapes["AWS::Test::Thing.Targets"] == {"type": "list", "member": "AWS::Test::Thing/Target"}
    assert shapes["AWS::Test::Thing/Target"] == {"type": "structure", "members": {
        "Id": "String", "Retry": "AWS::Test::Thing/Retry", "Labels": "AWS::Test::Thing/Target.Labels",
        "Headers": "AWS::Test::Thing/Target.Headers", "Body": "Json", "Children": "AWS::Test::Thing/Target.Children"},
        "required": ["Id"]}
    assert shapes["AWS::Test::Thing/Target.Labels"] == {"type": "list", "member": "String"}
    assert shapes["AWS::Test::Thing/Target.Headers"] == {"type": "map", "value": "String"}
    assert shapes["AWS::Test::Thing/Target.Children"] == {"type": "list", "member": "AWS::Test::Thing/Target"}
    assert shapes["AWS::Test::Thing/Retry"] == {"type": "structure", "members": {
        "Attempts": "Integer", "Enabled": "Boolean", "Ratio": "Double"}}
    assert shapes["AWS::Test::Thing.Tags[]"] == {"type": "structure", "members": {"Key": "String"}}
    for name, kind in [("String", "string"), ("Integer", "integer"), ("Boolean", "boolean"), ("Double", "double"),
                       ("Json", "json")]:
        assert shapes[name] == {"type": kind}


def test_the_vendored_file_is_well_formed():
    assert r.validate(json.loads(r.OUTPUT.read_text(encoding="utf-8"))) == []


def test_validate_names_a_missing_shape_and_a_broken_anchor():
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    doc["shapes"]["AWS::Events::Rule/RetryPolicy"]["members"]["MaximumRetryAttempts"] = "String"
    doc["shapes"]["AWS::Events::Rule/Target"]["members"]["Gone"] = "AWS::Events::Rule/Gone"
    doc["shapes"]["AWS::ECS::TaskDefinition/Tmpfs"]["required"] = ["Size", "Absent"]

    problems = r.validate(doc)

    assert any("missing shape 'AWS::Events::Rule/Gone'" in problem for problem in problems)
    assert any("AWS::Events::Rule/RetryPolicy.MaximumRetryAttempts" in problem for problem in problems)
    assert any("requires 'Absent', which it does not declare" in problem for problem in problems)
    assert any("AWS::ECS::TaskDefinition/Tmpfs to require ['Size']" in problem for problem in problems)


def test_verify_reports_a_schema_aws_has_changed(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "RESOURCE_TYPES", ["AWS::Test::Thing"])
    monkeypatch.setattr(r, "ANCHORS", {})
    source = tmp_path / "schemas"
    source.mkdir()
    (source / "aws-test-thing.json").write_text(json.dumps(SCHEMA), encoding="utf-8")
    output = tmp_path / "types.json"
    output.write_text(r.render(r.build({"AWS::Test::Thing": SCHEMA}, "earlier")), encoding="utf-8")

    assert r.main(["--verify", "--source-dir", str(source), "--output", str(output)]) == 0

    changed = json.loads(json.dumps(SCHEMA))
    changed["definitions"]["Retry"]["properties"]["Attempts"]["type"] = "string"
    (source / "aws-test-thing.json").write_text(json.dumps(changed), encoding="utf-8")

    assert r.main(["--verify", "--source-dir", str(source), "--output", str(output)]) == 1


def test_verify_exits_2_when_the_schemas_cannot_be_read(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "RESOURCE_TYPES", ["AWS::Test::Thing"])
    output = tmp_path / "types.json"
    output.write_text(r.render(r.build({"AWS::Test::Thing": SCHEMA}, "earlier")), encoding="utf-8")

    assert r.main(["--verify", "--source-dir", str(tmp_path / "absent"), "--output", str(output)]) == 2
