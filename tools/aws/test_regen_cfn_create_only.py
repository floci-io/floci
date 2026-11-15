"""Tests for regen_cfn_create_only.

Run with: pytest tools/aws -q  (or: make aws-data-test)

Interface assumed from the specification (floci-createonly-2026-11-12/design.md), same shape as
regen_cfn_property_types.py:
  - module regen_cfn_create_only with main(argv) -> int, exit 0 ok, 1 verify mismatch or a missing
    schema, 2 when the schemas cannot be read;
  - flags --source-dir DIR (saved aws-<service>-<type>.json files), --output FILE, --verify;
  - module attribute INVENTORY: the tab-separated inventory file (type in column 1), patched here;
  - module attribute SCHEMALESS: the reasoned list of inventory types with no registry schema
    (a set or a type-to-reason dict; patched here with a dict), today AWS::CDK::Metadata and
    Custom::DynamoDBReplica;
  - the output is a plain JSON object, type name to list of paths, no metadata key (the Java
    loader reads it as Map<String, Set<String>>).
"""
from __future__ import annotations

import json

import regen_cfn_create_only as r

# Hand-written schemas; expected values below are written by hand from the specification.
WIDGET = {
    "typeName": "AWS::Test::Widget",
    "properties": {"Name": {"type": "string"}},
    "createOnlyProperties": ["/properties/Name", "/properties/Network/Subnet/Id", "/properties/Zone"],
}
GADGET = {"typeName": "AWS::Test::Gadget", "properties": {"Label": {"type": "string"}}}


def _setup(tmp_path, monkeypatch, types, schemas, schemaless):
    inventory = tmp_path / "inventory.tsv"
    inventory.write_text("".join(f"{t}\tSomeProvisioner\n" for t in types), encoding="utf-8")
    source = tmp_path / "schemas"
    source.mkdir()
    for name, schema in schemas.items():
        (source / (name.lower().replace("::", "-") + ".json")).write_text(json.dumps(schema), encoding="utf-8")
    monkeypatch.setattr(r, "INVENTORY", inventory)
    monkeypatch.setattr(r, "SCHEMALESS", schemaless)
    return source, tmp_path / "create-only.json"


def test_paths_lose_the_properties_prefix_nested_paths_stay_and_no_list_means_empty(tmp_path, monkeypatch):
    source, output = _setup(tmp_path, monkeypatch,
                            ["AWS::Test::Gadget", "AWS::Test::Widget", "Custom::Reasoned"],
                            {"AWS::Test::Widget": WIDGET, "AWS::Test::Gadget": GADGET},
                            {"Custom::Reasoned": "emitted by a toolchain, no registry schema"})

    assert r.main(["--source-dir", str(source), "--output", str(output)]) == 0

    assert json.loads(output.read_text(encoding="utf-8")) == {
        "AWS::Test::Gadget": [],
        "AWS::Test::Widget": ["Name", "Network/Subnet/Id", "Zone"],
    }


def test_a_type_without_a_schema_is_refused_unless_it_is_in_the_reasoned_list(tmp_path, monkeypatch, capsys):
    source, output = _setup(tmp_path, monkeypatch, ["AWS::Test::Gadget", "AWS::Test::Widget"],
                            {"AWS::Test::Widget": WIDGET}, {})

    assert r.main(["--source-dir", str(source), "--output", str(output)]) != 0
    assert "AWS::Test::Gadget" in capsys.readouterr().err
    assert not output.exists()

    monkeypatch.setattr(r, "SCHEMALESS", {"AWS::Test::Gadget": "has no registry schema"})

    assert r.main(["--source-dir", str(source), "--output", str(output)]) == 0
    assert json.loads(output.read_text(encoding="utf-8")) == {"AWS::Test::Widget": ["Name", "Network/Subnet/Id", "Zone"]}


def test_verify_fails_on_a_hand_edited_entry(tmp_path, monkeypatch):
    source, output = _setup(tmp_path, monkeypatch, ["AWS::Test::Widget"], {"AWS::Test::Widget": WIDGET}, {})
    assert r.main(["--source-dir", str(source), "--output", str(output)]) == 0

    assert r.main(["--verify", "--source-dir", str(source), "--output", str(output)]) == 0

    edited = json.loads(output.read_text(encoding="utf-8"))
    edited["AWS::Test::Widget"].append("Extra")
    output.write_text(json.dumps(edited, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    assert r.main(["--verify", "--source-dir", str(source), "--output", str(output)]) == 1
