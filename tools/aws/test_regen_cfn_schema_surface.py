"""Tests for regen_cfn_schema_surface.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

import regen_cfn_schema_surface as r

SCHEMA = {
    "typeName": "AWS::Test::Thing",
    "properties": {
        "Arn": {"type": "string"},
        "Name": {"type": "string"},
        "Old": {"type": "string"},
        "Secret": {"type": "string"},
        "Nested": {"type": "object", "properties": {"Id": {"type": "string"}}},
    },
    "readOnlyProperties": ["/properties/Arn", "/properties/Nested/Id"],
    "deprecatedProperties": ["/properties/Old"],
    "writeOnlyProperties": ["/properties/Secret"],
}


def test_surface_splits_attributes_from_properties():
    read_only, writable = r.surface(SCHEMA)
    assert read_only == ["Arn"]
    assert writable == ["Name", "Nested", "Secret"]


def test_nested_pointers_are_not_top_level_names():
    assert r.top_level_names(["/properties/A", "/properties/B/C", "/properties/D/"]) == {"A"}


def test_file_name_follows_the_archive():
    assert r.file_name("AWS::Events::Rule") == "aws-events-rule.json"


def test_render_has_one_row_per_type_and_empty_columns_stay_columns():
    schemas = {"AWS::Test::Thing": SCHEMA, "AWS::Test::Bare": {"properties": {"Id": {}}}}
    assert r.render(schemas) == (
        "AWS::Test::Bare\t\tId\n"
        "AWS::Test::Thing\tArn\tName,Nested,Secret\n")


def test_sync_then_verify_from_a_saved_directory(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "SCHEMALESS", frozenset({"Custom::NoSchema"}))
    source = tmp_path / "schemas"
    source.mkdir()
    (source / "aws-test-thing.json").write_text(json.dumps(SCHEMA), encoding="utf-8")
    inventory = tmp_path / "inventory.tsv"
    inventory.write_text("AWS::Test::Thing\tThingProvisioner\nCustom::NoSchema\tOther\n", encoding="utf-8")
    output = tmp_path / "surface.tsv"
    args = ["--source-dir", str(source), "--inventory", str(inventory), "--output", str(output)]

    assert r.main(args) == 0
    assert output.read_text(encoding="utf-8") == "AWS::Test::Thing\tArn\tName,Nested,Secret\n"
    assert r.main(args + ["--verify"]) == 0

    output.write_text("AWS::Test::Thing\tArn\tName\n", encoding="utf-8")
    assert r.main(args + ["--verify"]) == 1


def test_a_trailing_slash_pointer_is_not_top_level():
    # The Java code this replaced split the pointer without stripping, so /properties/C/ had four parts.
    assert r.top_level_names(["/properties/A", "/properties/C/"]) == {"A"}


def _setup(tmp_path, schemas):
    source = tmp_path / "schemas"
    source.mkdir()
    for name, content in schemas.items():
        (source / name).write_text(content, encoding="utf-8")
    inventory = tmp_path / "inventory.tsv"
    inventory.write_text("AWS::Test::Thing\tP\nAWS::Test::Other\tP\nCustom::NoSchema\tP\n", encoding="utf-8")
    output = tmp_path / "surface.tsv"
    args = ["--source-dir", str(source), "--inventory", str(inventory), "--output", str(output)]
    return args, output


def test_a_missing_schema_fails_by_name_and_writes_nothing(tmp_path, capsys, monkeypatch):
    monkeypatch.setattr(r, "SCHEMALESS", frozenset({"Custom::NoSchema"}))
    args, output = _setup(tmp_path, {"aws-test-thing.json": json.dumps(SCHEMA)})
    assert r.main(args) == 1
    assert "AWS::Test::Other" in capsys.readouterr().err
    assert not output.exists()
    assert r.main(args + ["--verify"]) == 1


def test_an_empty_source_dir_fails_and_writes_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "SCHEMALESS", frozenset({"Custom::NoSchema"}))
    args, output = _setup(tmp_path, {})
    assert r.main(args) == 1
    assert not output.exists()


@pytest.mark.parametrize("schema", [
    {"properties": {"A": {}}, "readOnlyProperties": [1]},
    {"properties": {"A": {}}, "readOnlyProperties": [None]},
    {"properties": {"A": {}}, "deprecatedProperties": [["x"]]},
    {"properties": ["A"]},
    {"properties": {"A": {}}, "readOnlyProperties": "/properties/A"},
])
def test_a_malformed_schema_is_a_read_failure_not_a_difference(tmp_path, monkeypatch, schema):
    monkeypatch.setattr(r, "SCHEMALESS", frozenset({"Custom::NoSchema"}))
    both = {"aws-test-thing.json": json.dumps(schema), "aws-test-other.json": json.dumps(SCHEMA)}
    args, output = _setup(tmp_path, both)
    assert r.main(args) == 2
    assert r.main(args + ["--verify"]) == 2
    assert not output.exists()


def test_an_incomplete_read_is_a_read_failure(tmp_path, monkeypatch, capsys):
    import http.client

    def broken(*_args):
        raise http.client.IncompleteRead(b"partial")

    monkeypatch.setattr(r, "fetch_schemas", broken)
    args, _ = _setup(tmp_path, {})
    assert r.main(args[2:]) == 2
    assert "could not read" in capsys.readouterr().err
