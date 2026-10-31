"""Action keys and request members for the S3 Control tag condition key table."""
import json
import subprocess

import pytest

from regen_s3control_tag_condition_keys import OUTPUT, action_keys, request_members, validate


def test_action_and_resource_keys_are_combined_and_filtered():
    reference = {"Name": "s3", "Resources": [{"Name": "job", "ConditionKeys": ["aws:RequestTag/${TagKey}", "s3:JobSuspendedCause"]}],
                 "Actions": [{"Name": "TagResource", "ActionConditionKeys": ["aws:TagKeys", "s3:authType"]},
                             {"Name": "PutJobTagging", "Resources": [{"Name": "job"}]},
                             {"Name": "ListAccessPoints", "ActionConditionKeys": ["s3:authType"]}]}
    assert action_keys(reference) == {"s3:PutJobTagging": ["aws:RequestTag/${TagKey}"],
                                      "s3:TagResource": ["aws:TagKeys"]}


def test_body_tags_feed_both_keys_and_query_tag_keys_feed_tag_keys():
    model = {"metadata": {"protocol": "rest-xml"}, "operations": {
        "TagResource": {"input": {"shape": "TagIn"}},
        "UntagResource": {"input": {"shape": "UntagIn"}},
        "CreateJob": {"input": {"shape": "JobIn"}},
        "ListAccessPoints": {"input": {"shape": "ListIn"}}}, "shapes": {
        "TagIn": {"type": "structure", "members": {"ResourceArn": {"shape": "String", "location": "uri"},
                                                   "Tags": {"shape": "TagList"}}},
        "UntagIn": {"type": "structure", "members": {
            "TagKeys": {"shape": "KeyList", "location": "querystring", "locationName": "tagKeys"}}},
        "JobIn": {"type": "structure", "members": {"Manifest": {"shape": "Manifest"}}},
        "Manifest": {"type": "structure", "members": {"Tags": {"shape": "TagList"}}},
        "ListIn": {"type": "structure", "members": {"Bucket": {"shape": "String", "location": "querystring"}}},
        "TagList": {"type": "list", "member": {"shape": "Tag", "locationName": "Tag"}},
        "Tag": {"type": "structure", "members": {"Key": {"shape": "String"}, "Value": {"shape": "String"}}},
        "KeyList": {"type": "list", "member": {"shape": "String"}},
        "String": {"type": "string"}}}
    tags = {"member": "Tags[Tag]", "key": "Key", "value": "Value"}
    assert request_members(model) == {
        "s3:TagResource": {"aws:RequestTag/${TagKey}": [tags], "aws:TagKeys": [tags]},
        "s3:UntagResource": {"aws:TagKeys": [{"member": "tagKeys", "location": "querystring"}]}}


def test_the_vendored_table_reads_tag_resource_and_untag_resource():
    document = json.loads(OUTPUT.read_text())
    assert validate(document) == []
    assert document["actions"]["s3:TagResource"] == ["aws:RequestTag/${TagKey}", "aws:TagKeys"]
    assert "aws:TagKeys" in document["actions"]["s3:UntagResource"]
    assert document["members"]["s3:TagResource"]["aws:RequestTag/${TagKey}"] == [
        {"member": "Tags[Tag]", "key": "Key", "value": "Value"}]
    assert document["members"]["s3:UntagResource"] == {
        "aws:TagKeys": [{"member": "tagKeys", "location": "querystring"}]}


@pytest.mark.parametrize("changed", [
    "src/main/resources/aws/s3control-tag-condition-keys.json",
    "tools/aws/regen_s3control_tag_condition_keys.py",
    "tools/aws/test_regen_s3control_tag_condition_keys.py",
    "tools/aws/regen_iam_tag_on_create.py",
    "",
])
def test_aggregate_gate_checks_the_table_and_its_imports(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:s3control-tag-condition-keys-verify" in result.stdout
    assert result.returncode != 0
    assert "the S3 Control tag condition key table" in result.stdout


@pytest.mark.parametrize("changed", [
    "docs/services/s3.md",
    "src/main/resources/aws/dynamodb-condition-keys.json",
    "src/main/resources/aws/s3control-tag-condition-keys.json.bak",
])
def test_aggregate_gate_does_not_check_the_table_for_unrelated_files(tmp_path, changed):
    result = run_aggregate_gate(tmp_path, changed, fail=True)
    assert "checked:s3control-tag-condition-keys-verify" not in result.stdout
    assert result.returncode == 0


def run_aggregate_gate(tmp_path, changed, fail):
    recursive_make = tmp_path / "check-gate"
    recursive_make.write_text(
        '#!/bin/sh\nprintf "checked:%s\\n" "$2"\n'
        + ('[ "$2" != s3control-tag-condition-keys-verify ]\n' if fail else 'exit 0\n'))
    recursive_make.chmod(0o700)
    return subprocess.run(
        ["make", "--no-print-directory", "iam-data-verify", "CHANGED=" + changed,
         "MAKE=" + str(recursive_make)],
        cwd=OUTPUT.parents[4], capture_output=True, text=True, check=False)
