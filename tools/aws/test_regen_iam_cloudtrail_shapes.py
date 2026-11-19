"""Tests for regen_iam_cloudtrail_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import pytest

import regen_iam_cloudtrail_shapes as r


def model(shapes: dict, operations: dict) -> dict:
    return {"operations": operations, "shapes": shapes}


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


def test_each_operation_names_its_shapes_and_its_result_wrapper():
    assert r.operations_from(model(SHAPES, OPERATIONS)) == {
        "ChangePassword": {"input": "ChangePasswordRequest", "output": None, "resultWrapper": None},
        "CreateUser": {"input": "CreateUserRequest", "output": "CreateUserResponse",
                       "resultWrapper": "CreateUserResult"},
        "GenerateCredentialReport": {"input": None, "output": None, "resultWrapper": None},
    }


def test_shapes_keep_their_types_and_members_without_constraints_or_documentation():
    shapes = r.shapes_from(model(SHAPES, OPERATIONS))
    assert shapes["CreateUserRequest"] == {"type": "structure",
                                           "members": {"UserName": "userNameType", "Tags": "tagListType"}}
    assert shapes["tagListType"] == {"type": "list", "member": "Tag"}
    assert shapes["userNameType"] == {"type": "string"}
    assert shapes["dateType"] == {"type": "timestamp"}


def test_a_sensitive_shape_says_so():
    assert r.shapes_from(model(SHAPES, OPERATIONS))["passwordType"] == {"type": "string", "sensitive": True}


def test_only_the_shapes_an_operation_reaches_are_kept():
    shapes = r.shapes_from(model(SHAPES, OPERATIONS))
    assert "unreachable" not in shapes
    assert "NoSuchEntityException" not in shapes


def test_a_map_keeps_its_key_and_value_shapes():
    shapes = dict(SHAPES, summaryMapType={"type": "map", "key": {"shape": "tagKeyType"},
                                          "value": {"shape": "userNameType"}},
                  GetAccountSummaryResponse={"type": "structure",
                                             "members": {"SummaryMap": {"shape": "summaryMapType"}}})
    operations = {"GetAccountSummary": {"output": {"shape": "GetAccountSummaryResponse",
                                                   "resultWrapper": "GetAccountSummaryResult"}}}
    assert r.shapes_from(model(shapes, operations))["summaryMapType"] == {
        "type": "map", "key": "tagKeyType", "value": "userNameType"}


def test_a_shape_type_the_record_does_not_read_stops_the_generation():
    shapes = dict(SHAPES, userNameType={"type": "document"})
    with pytest.raises(ValueError, match="does not read"):
        r.shapes_from(model(shapes, OPERATIONS))


def test_a_member_trait_the_record_does_not_read_stops_the_generation():
    shapes = dict(SHAPES, User={"type": "structure", "members": {
        "UserName": {"shape": "userNameType", "locationName": "name"}}})
    with pytest.raises(ValueError, match="locationName"):
        r.shapes_from(model(shapes, OPERATIONS))


def test_errors_map_the_code_they_answer_with_to_their_shape():
    assert r.errors_from(model(SHAPES, OPERATIONS)) == {
        "NoSuchEntity": "NoSuchEntityException",
        "RoleModifiedException": "RoleModifiedException",
    }


def test_two_errors_answering_one_code_stop_the_generation():
    shapes = dict(SHAPES, EntityMissingException={"type": "structure", "members": {}, "exception": True,
                                                  "error": {"code": "NoSuchEntity"}})
    with pytest.raises(ValueError, match="both answer NoSuchEntity"):
        r.errors_from(model(shapes, OPERATIONS))


def test_the_document_names_its_generator_and_source():
    document = r.build(model(SHAPES, OPERATIONS), "botocore test")
    assert document["_source"] == {"generator": "tools/aws/regen_iam_cloudtrail_shapes.py",
                                   "botocore": "botocore test"}
    assert list(document) == ["_source", "operations", "shapes", "errors"]
