"""Action coverage and request members for the Lambda Web Functions authType condition keys."""
import json
import subprocess

import pytest

from regen_lambda_web_condition_keys import OUTPUT, action_keys, request_members, validate


def test_resource_and_action_keys_are_combined():
    reference = {"Name": "lambda", "Resources": [{"Name": "webFunctionEndpoint",
                                                  "ConditionKeys": ["lambda:Resource/WebFunctionAuthType"]}],
                 "Actions": [{"Name": "UpdateWebFunctionEndpoint",
                              "ActionConditionKeys": ["lambda:Request/WebFunctionAuthType", "aws:TagKeys"],
                              "Resources": [{"Name": "webFunctionEndpoint"}]},
                             {"Name": "GetWebFunction", "ActionConditionKeys": ["aws:ResourceTag/${TagKey}"]}]}
    assert action_keys(reference) == {"lambda:UpdateWebFunctionEndpoint": [
        "lambda:Request/WebFunctionAuthType", "lambda:Resource/WebFunctionAuthType"]}


def test_a_nested_member_is_found_and_a_resource_key_has_none():
    model = {"operations": {"CreateWebFunction": {"input": {"shape": "Input"}},
                            "GetWebFunctionEndpoint": {"input": {"shape": "Get"}}}, "shapes": {
        "Input": {"members": {"endpointConfig": {"shape": "Config"}, "functionName": {"shape": "String"}}},
        "Config": {"members": {"authType": {"shape": "AuthType"}}},
        "Get": {"members": {"endpointName": {"shape": "String"}}}, "AuthType": {}, "String": {}}}
    assert request_members(model, {"lambda:CreateWebFunction": ["lambda:Request/WebFunctionAuthType"],
                                   "lambda:GetWebFunctionEndpoint": ["lambda:Resource/WebFunctionAuthType"]}) == {
        "lambda:CreateWebFunction": {"lambda:Request/WebFunctionAuthType": ["endpointConfig.authType"]}}


def test_a_request_key_without_a_member_fails():
    model = {"operations": {"CreateWebFunctionEndpoint": {"input": {"shape": "Input"}}},
             "shapes": {"Input": {"members": {"endpointName": {"shape": "String"}}}, "String": {}}}
    with pytest.raises(ValueError, match="no authType member"):
        request_members(model, {"lambda:CreateWebFunctionEndpoint": ["lambda:Request/WebFunctionAuthType"]})


def test_the_vendored_table_covers_the_endpoint_routes():
    document = json.loads(OUTPUT.read_text())
    assert validate(document) == []
    assert document["actions"]["lambda:UpdateWebFunctionEndpoint"] == [
        "lambda:Request/WebFunctionAuthType", "lambda:Resource/WebFunctionAuthType"]
    assert document["members"]["lambda:CreateWebFunction"] == {
        "lambda:Request/WebFunctionAuthType": ["endpointConfig.authType"]}


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/lambda-web-condition-keys.json",
    "tools/aws/regen_lambda_web_condition_keys.py",
    "tools/aws/test_regen_lambda_web_condition_keys.py",
    "tools/aws/regen_dynamodb_condition_keys.py",
    "tools/aws/regen_iam_tag_on_create.py",
    "tools/aws/regen_iam_passed_roles.py",
    "tools/aws/regen_partitions.py",
    "tools/aws/regen_query_services.py",
    "",
])
def test_aggregate_gate_checks_the_table_and_its_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:lambda-web-condition-keys-verify" in result.stdout
    assert result.returncode != 0
    assert "the Lambda Web condition key table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/lambda-web-functions.md",
    "src/main/resources/aws/dynamodb-condition-keys.json",
    "src/main/resources/aws/lambda-web-condition-keys.json.bak",
])
def test_aggregate_gate_does_not_check_unrelated_data(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:lambda-web-condition-keys-verify" not in result.stdout
    assert result.returncode == 0


def test_aggregate_gate_accepts_a_successful_check(tmp_path):
    result = run_aggregate_gate(tmp_path, "src/main/resources/aws/lambda-web-condition-keys.json", fail=False)
    assert "checked:lambda-web-condition-keys-verify" in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != lambda-web-condition-keys-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=OUTPUT.parents[4], capture_output=True, text=True, check=False)
