"""Tests for regen_iam_resource_tag_actions.

Run with: pytest tools/aws -q  (or: make iam-resource-tag-actions-test)
"""
from __future__ import annotations

import regen_iam_resource_tag_actions as r


def service(namespace: str, resources: dict[str, list[str]], actions: list[tuple[str, list[str]]]) -> dict:
    """A reference document shaped like AWS's."""
    return {
        "Name": namespace,
        "Resources": [
            {"Name": name, "ConditionKeys": keys} for name, keys in resources.items()
        ],
        "Actions": [
            {"Name": name, "Resources": [{"Name": type_name} for type_name in types]}
            for name, types in actions
        ],
    }


SQS = service(
    "sqs",
    {
        "queue": [r.RESOURCE_TAG_KEY],
        "other": [],
    },
    [
        ("SendMessage", ["queue"]),
        ("ReceiveMessage", ["queue"]),
        ("ListQueues", []),
        ("DescribeOther", ["other"]),
    ],
)

SNS = service(
    "sns",
    {"topic": [r.RESOURCE_TAG_KEY]},
    [
        ("Publish", ["topic"]),
        ("Subscribe", ["topic"]),
        ("ListPlatformApplications", []),
    ],
)


def test_only_actions_on_a_tagged_resource_type_are_kept():
    assert r.resource_tag_actions_from(SQS, "sqs") == [
        "sqs:ReceiveMessage",
        "sqs:SendMessage",
    ]


def test_an_action_on_an_untagged_resource_type_is_left_out():
    assert "sqs:DescribeOther" not in r.resource_tag_actions_from(SQS, "sqs")


def test_an_action_that_names_no_resource_is_left_out():
    assert "sqs:ListQueues" not in r.resource_tag_actions_from(SQS, "sqs")


def test_actions_are_namespace_prefixed_and_sorted():
    assert r.resource_tag_actions_from(SNS, "sns") == ["sns:Publish", "sns:Subscribe"]


def test_actions_from_keeps_every_namespace():
    found = r.actions_from({"sqs": SQS, "sns": SNS})
    assert list(found) == ["sns", "sqs"]
    assert found["sns"] == ["sns:Publish", "sns:Subscribe"]
    assert found["sqs"] == ["sqs:ReceiveMessage", "sqs:SendMessage"]


def test_an_unusable_namespace_is_rejected():
    try:
        r.resource_tag_actions_from(SQS, "Not A Namespace")
    except ValueError:
        pass
    else:
        raise AssertionError("an unusable namespace was accepted")


def test_a_valid_document_passes_the_offline_check():
    document = {
        "namespaces": {
            "sns": ["sns:Publish", "sns:Subscribe"],
            "sqs": ["sqs:ReceiveMessage", "sqs:SendMessage"],
        }
    }
    assert r.validate(document) == []


def test_unsorted_actions_are_rejected():
    document = {
        "namespaces": {
            "sns": ["sns:Publish", "sns:Subscribe"],
            "sqs": ["sqs:SendMessage", "sqs:ReceiveMessage"],
        }
    }
    assert any("not sorted" in problem for problem in r.validate(document))


def test_a_batch_operation_is_rejected_as_an_action():
    document = {
        "namespaces": {
            "sns": ["sns:Publish", "sns:Subscribe"],
            "sqs": ["sqs:ReceiveMessage", "sqs:SendMessage", "sqs:SendMessageBatch"],
        }
    }
    assert any("not an IAM action" in problem for problem in r.validate(document))


def test_a_missing_anchor_is_rejected():
    document = {
        "namespaces": {
            "sns": ["sns:Publish", "sns:Subscribe"],
            "sqs": ["sqs:ReceiveMessage"],
        }
    }
    assert any("sqs:SendMessage" in problem for problem in r.validate(document))


def test_an_action_of_another_namespace_is_rejected():
    document = {
        "namespaces": {
            "sns": ["sns:Publish", "sns:Subscribe"],
            "sqs": ["sns:Publish", "sqs:ReceiveMessage", "sqs:SendMessage"],
        }
    }
    assert any("not its action" in problem for problem in r.validate(document))


def test_a_missing_namespaces_map_is_reported():
    assert r.validate({}) == ["namespaces is missing or empty"]


def test_verify_reports_a_removed_action_and_an_added_one():
    untrue, added = r.verify_against(
        {"sqs": ["sqs:SendMessage", "sqs:OldAction"]},
        {"sqs": ["sqs:SendMessage", "sqs:NewAction"]},
    )
    assert untrue == ["'sqs:OldAction' does not act on a resource carrying aws:ResourceTag"]
    assert added == ["sqs:NewAction (new action)"]


def test_build_carries_provenance_and_sorted_namespaces():
    document = r.build({"sqs": SQS, "sns": SNS}, "https://example.test/service.json")
    assert document["_source"]["generator"] == "tools/aws/regen_iam_resource_tag_actions.py"
    assert document["_source"]["reference"] == "https://example.test/service.json"
    assert list(document["namespaces"]) == ["sns", "sqs"]
