"""Action coverage and request members for the Redshift condition keys."""
import json
import subprocess

import pytest

from regen_redshift_condition_keys import OUTPUT, action_keys, request_members, validate


def test_resource_and_action_keys_are_combined_and_only_the_read_keys_kept():
    reference = {"Name": "redshift", "Resources": [{"Name": "dbuser"}],
                 "Actions": [{"Name": "GetClusterCredentials",
                              "ActionConditionKeys": ["redshift:DbName", "redshift:DbUser",
                                                      "redshift:DurationSeconds"],
                              "Resources": [{"Name": "dbuser"}]},
                             {"Name": "JoinGroup", "Resources": [{"Name": "dbgroup"}]}]}
    assert action_keys(reference) == {"redshift:GetClusterCredentials": ["redshift:DbUser"]}


def test_the_member_is_named_after_the_key_ignoring_case():
    model = {"operations": {"GetClusterCredentials": {"input": {"shape": "Input"}}}, "shapes": {
        "Input": {"members": {"DBUSER": {"shape": "String"}, "ClusterIdentifier": {"shape": "String"}}},
        "String": {"type": "string"}}}
    assert request_members(model, {"redshift:GetClusterCredentials": ["redshift:DbUser"]}) == {
        "redshift:GetClusterCredentials": {"redshift:DbUser": "DBUSER"}}


def test_an_action_whose_request_does_not_carry_the_key_is_left_out():
    assert request_members({"operations": {}, "shapes": {}},
                           {"redshift:CreateClusterUser": ["redshift:DbUser"]}) == {}


def test_a_key_without_a_member_fails():
    model = {"operations": {"GetClusterCredentials": {"input": {"shape": "Input"}}}, "shapes": {
        "Input": {"members": {"DbUser": {"shape": "Names"}}}, "Names": {"type": "list"}}}
    with pytest.raises(ValueError, match="no string member"):
        request_members(model, {"redshift:GetClusterCredentials": ["redshift:DbUser"]})


def test_the_vendored_table_reads_the_database_user():
    document = json.loads(OUTPUT.read_text())
    assert validate(document) == []
    assert document["actions"]["redshift:CreateClusterUser"] == ["redshift:DbUser"]
    assert document["members"] == {"redshift:GetClusterCredentials": {"redshift:DbUser": "DbUser"}}


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/redshift-condition-keys.json",
    "tools/aws/regen_redshift_condition_keys.py",
    "tools/aws/test_regen_redshift_condition_keys.py",
    "tools/aws/regen_iam_tag_on_create.py",
    "tools/aws/regen_iam_passed_roles.py",
    "tools/aws/regen_partitions.py",
    "tools/aws/regen_query_services.py",
    "",
])
def test_aggregate_gate_checks_the_table_and_its_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:redshift-condition-keys-verify" in result.stdout
    assert result.returncode != 0
    assert "the Redshift condition key table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/redshift.md",
    "src/main/resources/aws/iam-condition-keys.json",
    "src/main/resources/aws/redshift-condition-keys.json.bak",
])
def test_aggregate_gate_does_not_check_unrelated_data(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:redshift-condition-keys-verify" not in result.stdout
    assert result.returncode == 0


def test_aggregate_gate_accepts_a_successful_check(tmp_path):
    result = run_aggregate_gate(tmp_path, "src/main/resources/aws/redshift-condition-keys.json", fail=False)
    assert "checked:redshift-condition-keys-verify" in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != redshift-condition-keys-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=OUTPUT.parents[4], capture_output=True, text=True, check=False)
