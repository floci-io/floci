"""Tests for regen_cfn_resource_types.

Run with: pytest tools/docs -q  (or: make docs-test)
"""
from __future__ import annotations

import pytest

import regen_cfn_resource_types as r


BASE_CONFIG = {
    "order": ["AWS::S3", "AWS::CloudFormation"],
    "service_labels": {
        "AWS::S3": "S3",
        "AWS::CloudFormation": "CloudFormation",
        "AWS::SQS": "SQS",
    },
}


def render(inventory, **overrides):
    config = {**BASE_CONFIG, **overrides}
    return r.render_rows(inventory, config)


# --------------------------------------------------------------------------- #
# Type naming
# --------------------------------------------------------------------------- #
def test_namespace_and_leaf_of_a_three_segment_type():
    assert r.namespace_of("AWS::SQS::Queue") == "AWS::SQS"
    assert r.leaf_of("AWS::SQS::Queue") == "Queue"


def test_two_segment_type_keeps_its_prefix():
    # Abbreviating Custom::DynamoDBReplica to DynamoDBReplica would read as an AWS
    # resource type that does not exist.
    assert r.namespace_of("Custom::DynamoDBReplica") == "Custom"
    assert r.leaf_of("Custom::DynamoDBReplica") == "Custom::DynamoDBReplica"


# --------------------------------------------------------------------------- #
# Rendering
# --------------------------------------------------------------------------- #
def test_renders_one_row_per_namespace_in_configured_order():
    lines, warnings = render(
        [("AWS::SQS::Queue", "SqsCfnProvisioner"), ("AWS::S3::Bucket", "LEGACY_SWITCH")]
    )
    assert lines[0] == "| Service | Resource types |"
    # S3 is in the order list, SQS is not, so SQS is appended after it.
    assert lines[2] == "| S3 | `Bucket` |"
    assert lines[3] == "| SQS | `Queue` |"
    assert any("not in the order list" in w for w in warnings)


def test_unlisted_types_sort_alphabetically_after_curated_ones():
    inventory = [
        ("AWS::S3::AccessPoint", "LEGACY_SWITCH"),
        ("AWS::S3::Bucket", "LEGACY_SWITCH"),
        ("AWS::S3::BucketPolicy", "LEGACY_SWITCH"),
    ]
    lines, _ = render(inventory, type_order={"AWS::S3": ["Bucket", "BucketPolicy"]})
    assert lines[2] == "| S3 | `Bucket`, `BucketPolicy`, `AccessPoint` |"


def test_notes_and_row_notes_are_appended():
    lines, _ = render(
        [("AWS::S3::Bucket", "LEGACY_SWITCH")],
        notes={"AWS::S3::Bucket": "accepted; policy not enforced"},
        row_notes={"AWS::S3": "Trailing prose."},
    )
    assert lines[2] == "| S3 | `Bucket` (accepted; policy not enforced). Trailing prose. |"


def test_display_names_override_the_leaf():
    lines, _ = render(
        [("AWS::CDK::Metadata", "LEGACY_SWITCH")],
        service_labels={**BASE_CONFIG["service_labels"], "AWS::CDK": "CDK"},
        display_names={"AWS::CDK::Metadata": "CDK::Metadata"},
    )
    assert "`CDK::Metadata`" in lines[2]


def test_merged_namespace_folds_into_one_row():
    lines, _ = render(
        [
            ("AWS::CloudFormation::CustomResource", "LEGACY_SWITCH"),
            ("Custom::DynamoDBReplica", "LEGACY_SWITCH"),
        ],
        merge_namespaces={"Custom": "AWS::CloudFormation"},
    )
    rows = [line for line in lines if line.startswith("| CloudFormation ")]
    assert len(rows) == 1, "Custom::* belongs in the CloudFormation row, not a second one"
    assert "`Custom::DynamoDBReplica`" in rows[0]


def test_extra_types_appear_after_inventory_types():
    lines, _ = render(
        [("AWS::CloudFormation::CustomResource", "LEGACY_SWITCH")],
        extra_types={
            "AWS::CloudFormation": [
                {"name": "Stack", "note": "nested stacks", "reason": "not a provisioner type"}
            ]
        },
    )
    assert lines[2] == "| CloudFormation | `CustomResource`, `Stack` (nested stacks) |"


# --------------------------------------------------------------------------- #
# Warnings: these are the drift the gate exists to catch
# --------------------------------------------------------------------------- #
def test_warns_when_a_namespace_has_no_label():
    _, warnings = render([("AWS::Athena::WorkGroup", "AthenaCfnProvisioner")])
    assert any("no service_labels entry for AWS::Athena" in w for w in warnings)


def test_warns_when_type_order_names_a_type_nothing_provisions():
    _, warnings = render(
        [("AWS::S3::Bucket", "LEGACY_SWITCH")],
        type_order={"AWS::S3": ["Bucket", "Buckett"]},
    )
    assert any("lists Buckett" in w for w in warnings)


def test_warns_when_an_extra_type_has_no_reason():
    _, warnings = render(
        [("AWS::CloudFormation::CustomResource", "LEGACY_SWITCH")],
        extra_types={"AWS::CloudFormation": [{"name": "Stack"}]},
    )
    assert any("has no reason" in w for w in warnings)


def test_warns_when_a_type_is_claimed_by_both_the_switch_and_a_provisioner():
    # The registry wins, so the switch arm is dead code that still looks live.
    warnings = r.check_no_double_ownership(
        [("AWS::SQS::Queue", "LEGACY_SWITCH"), ("AWS::SQS::Queue", "SqsCfnProvisioner")]
    )
    assert len(warnings) == 1
    assert "SqsCfnProvisioner" in warnings[0]


def test_no_double_ownership_warning_when_each_type_has_one_owner():
    assert r.check_no_double_ownership(
        [("AWS::SQS::Queue", "SqsCfnProvisioner"), ("AWS::S3::Bucket", "LEGACY_SWITCH")]
    ) == []


# --------------------------------------------------------------------------- #
# Inventory parsing and splicing
# --------------------------------------------------------------------------- #
def test_load_inventory_rejects_a_malformed_row(tmp_path):
    path = tmp_path / "inv.tsv"
    path.write_text("AWS::SQS::Queue\tSqsCfnProvisioner\nAWS::S3::Bucket\n", encoding="utf-8")
    with pytest.raises(ValueError, match="expected 'type<TAB>owner'"):
        r.load_inventory(path)


def test_splice_replaces_only_the_marked_block():
    md = f"before\n{r.MARKER_START}\nold table\n{r.MARKER_END}\nafter\n"
    assert r.splice(md, ["new table"]) == (
        f"before\n{r.MARKER_START}\nnew table\n{r.MARKER_END}\nafter\n"
    )


def test_splice_requires_exactly_one_marker_pair():
    with pytest.raises(ValueError, match="exactly one"):
        r.splice("no markers here\n", ["x"])
