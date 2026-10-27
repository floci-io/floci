"""Semantic mapping and recursive request-member coverage for DynamoDB IAM conditions."""
import json
import subprocess

import pytest

from regen_dynamodb_condition_keys import OUTPUT, action_keys, member_paths, request_members


def test_resource_and_action_keys_are_combined():
    reference = {"Resources": [{"Name": "table", "ConditionKeys": ["aws:ResourceTag/${TagKey}"]}],
                 "Actions": [{"Name": "GetItem", "ActionConditionKeys": ["dynamodb:Select"],
                              "Resources": [{"Name": "table"}]}]}
    assert action_keys(reference) == {"dynamodb:GetItem": ["aws:ResourceTag/${TagKey}", "dynamodb:Select"]}


def test_nested_batch_member_paths_and_recursive_shapes():
    model = {"operations": {"BatchGetItem": {"input": {"shape": "Input"}}}, "shapes": {
        "Input": {"members": {"RequestItems": {"shape": "Map"}}},
        "Map": {"value": {"shape": "Request"}},
        "Request": {"members": {"ProjectionExpression": {"shape": "String"},
                                "Recursive": {"shape": "Request"}}}, "String": {}}}
    assert member_paths(model, "Input", {"ProjectionExpression"}) == ["RequestItems.*.ProjectionExpression"]
    assert request_members(model, {"dynamodb:BatchGetItem": ["dynamodb:Select"]}) == {
        "BatchGetItem": {"dynamodb:Select": ["RequestItems.*.ProjectionExpression"]}}


def test_all_five_select_actions_are_vendored():
    document = json.loads(OUTPUT.read_text())
    assert {action for action, keys in document["actions"].items() if "dynamodb:Select" in keys} == {
        "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:PartiQLSelect",
        "dynamodb:Query", "dynamodb:Scan"}
    assert "RequestItems.*.AttributesToGet" in document["members"]["BatchGetItem"]["dynamodb:Select"]
    assert "Statement" in document["members"]["ExecuteStatement"]["dynamodb:Select"]


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/dynamodb-condition-keys.json",
    "tools/aws/regen_dynamodb_condition_keys.py",
    "tools/aws/test_regen_dynamodb_condition_keys.py",
    "tools/aws/regen_partitions.py",
    "tools/aws/regen_query_services.py",
    "",
])
def test_aggregate_gate_checks_condition_data_and_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:dynamodb-condition-keys-verify" in result.stdout
    assert result.returncode != 0
    assert "the DynamoDB condition key table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/dynamodb.md",
    "src/main/resources/aws/iam-service-namespaces.json",
    "src/main/resources/aws/dynamodb-condition-keys.json.bak",
])
def test_aggregate_gate_does_not_check_unrelated_condition_data(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:dynamodb-condition-keys-verify" not in result.stdout
    assert result.returncode == 0


def test_aggregate_gate_accepts_successful_condition_check(tmp_path):
    result = run_aggregate_gate(tmp_path, "src/main/resources/aws/dynamodb-condition-keys.json", fail=False)
    assert "checked:dynamodb-condition-keys-verify" in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != dynamodb-condition-keys-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=OUTPUT.parents[4], capture_output=True, text=True, check=False)
