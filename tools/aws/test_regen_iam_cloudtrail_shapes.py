"""Tests for regen_iam_cloudtrail_shapes, and for what request_shapes.build keeps for it: each
operation's result and its wrapper, the sensitive flag and the modeled errors.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import pytest

import regen_iam_cloudtrail_shapes as cloudtrail
import request_shapes as r

GENERATOR = "tools/aws/regen_iam_cloudtrail_shapes.py"

SHAPES = {
    "CreateUserRequest": {"type": "structure", "members": {
        "UserName": {"shape": "userNameType", "documentation": "<p>The name.</p>"},
        "Tags": {"shape": "tagListType"}}},
    "CreateUserResponse": {"type": "structure", "members": {"User": {"shape": "User"}}},
    "User": {"type": "structure", "members": {
        "UserName": {"shape": "userNameType"}, "CreateDate": {"shape": "dateType"}}},
    "ChangePasswordRequest": {"type": "structure", "members": {
        "OldPassword": {"shape": "passwordType"}, "NewPassword": {"shape": "passwordType"}}},
    "tagListType": {"type": "list", "member": {"shape": "Tag"}},
    "Tag": {"type": "structure", "members": {"Key": {"shape": "tagKeyType"}, "Value": {"shape": "tagKeyType"}}},
    "userNameType": {"type": "string", "max": 64, "pattern": "[\\w+=,.@-]+"},
    "tagKeyType": {"type": "string"},
    "dateType": {"type": "timestamp"},
    "passwordType": {"type": "string", "sensitive": True},
    "NoSuchEntityException": {"type": "structure", "members": {}, "exception": True,
                              "error": {"code": "NoSuchEntity", "httpStatusCode": 404}},
    "RoleModifiedException": {"type": "structure", "members": {}, "exception": True},
    "unreachable": {"type": "string"},
}
OPERATIONS = {
    "CreateUser": {"input": {"shape": "CreateUserRequest"},
                   "output": {"shape": "CreateUserResponse", "resultWrapper": "CreateUserResult"}},
    "ChangePassword": {"input": {"shape": "ChangePasswordRequest"}},
    "GenerateCredentialReport": {},
}


def build(shapes: dict = SHAPES, operations: dict = OPERATIONS) -> dict:
    return r.build({"operations": operations, "shapes": shapes}, "botocore test", GENERATOR,
                   results=True, sensitive=True, errors=True, check=cloudtrail.readable)


def test_each_operation_names_its_input_and_each_result_its_shape_and_wrapper():
    document = build()
    assert document["operations"] == {"ChangePassword": "ChangePasswordRequest",
                                      "CreateUser": "CreateUserRequest"}
    assert document["results"] == {"CreateUser": {"shape": "CreateUserResponse",
                                                  "resultWrapper": "CreateUserResult"}}


def test_shapes_keep_their_types_and_members_without_constraints_or_documentation():
    shapes = build()["shapes"]
    assert shapes["CreateUserRequest"] == {"type": "structure",
                                           "members": {"UserName": "userNameType", "Tags": "tagListType"}}
    assert shapes["tagListType"] == {"type": "list", "member": "Tag"}
    assert shapes["userNameType"] == {"type": "string"}
    assert shapes["dateType"] == {"type": "timestamp"}
    assert shapes["User"] == {"type": "structure", "members": {"UserName": "userNameType",
                                                               "CreateDate": "dateType"}}


def test_a_sensitive_shape_says_so():
    assert build()["shapes"]["passwordType"] == {"type": "string", "sensitive": True}


def test_the_sensitive_flag_is_kept_only_when_asked_for():
    document = r.build({"operations": OPERATIONS, "shapes": SHAPES}, "botocore test", GENERATOR)
    assert document["shapes"]["passwordType"] == {"type": "string"}
    assert list(document) == ["_source", "operations", "shapes"]


def test_only_the_shapes_an_operation_reaches_are_kept():
    shapes = build()["shapes"]
    assert "unreachable" not in shapes
    assert "NoSuchEntityException" not in shapes


def test_a_shape_type_the_record_does_not_read_stops_the_generation():
    with pytest.raises(ValueError, match="does not read"):
        build(dict(SHAPES, userNameType={"type": "document"}))


def test_a_member_trait_the_record_does_not_read_stops_the_generation():
    shapes = dict(SHAPES, User={"type": "structure", "members": {
        "UserName": {"shape": "userNameType", "locationName": "name"}}})
    with pytest.raises(ValueError, match="locationName"):
        build(shapes)


def test_errors_map_the_code_they_answer_with_to_their_shape():
    assert build()["errors"] == {
        "NoSuchEntity": "NoSuchEntityException",
        "RoleModifiedException": "RoleModifiedException",
    }


def test_two_errors_answering_one_code_stop_the_generation():
    shapes = dict(SHAPES, EntityMissingException={"type": "structure", "members": {}, "exception": True,
                                                  "error": {"code": "NoSuchEntity"}})
    with pytest.raises(ValueError, match="both answer NoSuchEntity"):
        build(shapes)


def test_the_document_names_its_generator_and_source():
    document = build()
    assert document["_source"] == {"generator": GENERATOR, "botocore": "botocore test"}
    assert list(document) == ["_source", "operations", "results", "shapes", "errors"]
