#!/usr/bin/env python3
"""Tests for regen_cloudtrail_s3_data_events.py, on a vendored source in fetch_cloudtrail_s3_data_events.py's shape."""
from __future__ import annotations

import pytest

import regen_cloudtrail_s3_data_events as r


def source(general: list[str], recorded_as: list[tuple[str, str]] = ()) -> dict:
    return {
        "_source": {"page": "https://docs.aws.amazon.com/AmazonS3/latest/userguide/cloudtrail-logging-s3-info.html"},
        "dataEvents": {"AWS::S3::Object": general,
                       "AWS::S3::Access Point": ["CopyObject (same-region copies only)", "ListObjects", "ListObjectsV2"]},
        "recordedAs": [{"call": call, "eventName": name, "sentence": f"When you call {call}, ..."}
                       for call, name in recorded_as],
    }


def test_an_operation_the_row_lists_is_recorded_under_its_name():
    events = r.data_events(source(["GetObject", "CopyObject (same-region copies only)"]),
                           ["GetObject", "CopyObject", "GetBucketPolicy"])
    assert events == {"CopyObject": "CopyObject", "GetObject": "GetObject"}


def test_a_sentence_naming_the_event_a_call_is_recorded_with_renames_it():
    events = r.data_events(source(["PutObjectRetention"], [("PutObjectRetention", "PutObjectLockRetention")]),
                           ["PutObjectRetention"])
    assert events == {"PutObjectRetention": "PutObjectLockRetention"}


def test_a_versioned_operation_the_row_does_not_list_takes_the_listed_version_less_name():
    assert r.data_events(source(["ListObjects"]), ["ListObjects", "ListObjectsV2"]) == {
        "ListObjects": "ListObjects", "ListObjectsV2": "ListObjects"}
    # A row listing the versioned name too records it apart, and one listing neither records neither.
    assert r.data_events(source(["ListObjects", "ListObjectsV2"]), ["ListObjectsV2"]) == {
        "ListObjectsV2": "ListObjectsV2"}
    assert r.data_events(source(["GetObject"]), ["ListObjectsV2"]) == {}


def test_a_source_without_the_general_purpose_row_is_refused():
    broken = source(["GetObject"])
    del broken["dataEvents"]["AWS::S3::Object"]
    with pytest.raises(ValueError, match="no AWS::S3::Object row"):
        r.data_events(broken, ["GetObject"])


def reference(operations: dict[str, list[str]], writes: set[str]) -> dict:
    """An S3 service reference authorizing each operation with the actions given, those in `writes` mutating."""
    actions = sorted({action for listed in operations.values() for action in listed} | writes | {"GetObjectAnnotation"})
    return {
        "Actions": [{"Name": action, "Annotations": {"Properties": {
            "IsWrite": action in writes, "IsList": False, "IsPermissionManagement": False, "IsTaggingOnly": False}}}
            for action in actions],
        "Operations": [{"Name": operation, "AuthorizedActions": [{"Name": action, "Service": "s3"} for action in listed]
                        + [{"Name": "ListBucket", "Service": "s3-object-lambda"}]}
                       for operation, listed in operations.items()],
    }


def test_an_operation_is_read_only_when_none_of_its_actions_mutates():
    ref = reference({"GetObject": ["GetObject"], "CopyObject": ["GetObject", "PutObject"], "HeadBucket": ["ListBucket"]},
                    {"PutObject"})
    # An operation the reference lists no S3 action for is authorized by the action of its own name.
    assert r.read_only(ref, ["GetObject", "CopyObject", "HeadBucket", "GetObjectAnnotation"]) == {
        "GetObject": True, "CopyObject": False, "HeadBucket": True, "GetObjectAnnotation": True}
    with pytest.raises(ValueError, match="authorizes PutObjectAnnotation with no action"):
        r.read_only(ref, ["PutObjectAnnotation"])


def test_the_built_file_carries_each_event_s_name_and_read_only_flag():
    ref = reference({"GetObject": ["GetObject"], "ListObjects": ["ListBucket"], "ListObjectsV2": ["ListBucket"]}, set())
    built = r.build({"operations": {"GetObject": {}, "ListObjects": {}, "ListObjectsV2": {}}},
                    source(["GetObject", "ListObjects", "PutObjectAnnotation"]), ref, "botocore test", "tools/aws/x.py")
    assert built["dataEvents"] == {"GetObject": {"eventName": "GetObject", "readOnly": True},
                                   "ListObjects": {"eventName": "ListObjects", "readOnly": True},
                                   "ListObjectsV2": {"eventName": "ListObjects", "readOnly": True}}
    assert built["unmodeled"] == ["PutObjectAnnotation"]


def test_operations_recorded_under_one_name_must_agree_on_read_only():
    ref = reference({"ListObjects": ["ListBucket"], "ListObjectsV2": ["PutObject"]}, {"PutObject"})
    with pytest.raises(ValueError, match="not all read-only or all not"):
        r.build({"operations": {"ListObjects": {}, "ListObjectsV2": {}}}, source(["ListObjects"]), ref, "b", "g")


def test_check_and_verify_read_the_vendored_file_against_botocore_the_page_and_the_reference():
    model = {"operations": {"GetObject": {}, "PutObject": {}}}
    ref = reference({"GetObject": ["GetObject"], "PutObject": ["PutObject"]}, {"PutObject"})
    vendored = r.build(model, source(["GetObject", "PutObject"]), ref, "b", "g")
    assert r.check(vendored, model, source(["GetObject", "PutObject"])) == []
    assert r.verify(vendored, ref) == []
    assert r.check(vendored, model, source(["GetObject"])) == [
        "the operations or their event names differ from botocore's and the vendored page's"]
    assert r.verify(vendored, reference({"GetObject": ["GetObject"], "PutObject": ["PutObject"]}, set())) == [
        "PutObject is vendored readOnly False, the service reference gives True"]


def test_check_refuses_operations_recorded_under_one_name_that_disagree_on_read_only():
    model = {"operations": {"ListObjects": {}, "ListObjectsV2": {}}}
    ref = reference({"ListObjects": ["ListBucket"], "ListObjectsV2": ["ListBucket"]}, set())
    vendored = r.build(model, source(["ListObjects"]), ref, "b", "g")
    assert r.check(vendored, model, source(["ListObjects"])) == []
    vendored["dataEvents"]["ListObjectsV2"]["readOnly"] = False
    assert r.check(vendored, model, source(["ListObjects"])) == [
        "the operations recorded as ['ListObjects'] are not all read-only or all not"]
