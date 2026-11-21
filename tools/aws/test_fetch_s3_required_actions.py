#!/usr/bin/env python3
"""Tests for fetch_s3_required_actions.py, on pages in the S3 user guide's markup."""
from __future__ import annotations

import pytest

import fetch_s3_required_actions as f


def page(*rows: str) -> str:
    """A page whose main column holds the rows, each a line of the definition list the guide renders."""
    body = "".join(f"<p>{row}</p>" for row in rows)
    return f'<html><body><div id="main-col-body">{body}<div class="prev-next"></div></div></body></html>'


def test_each_operation_keeps_its_required_actions_and_its_section():
    operations = f.read_page(page(
        "Bucket operations and permissions",
        "ListObjectsV2",
        "(Required) s3:ListBucket",
        "Required to list some or all (up to 1,000) of the objects in an S3 bucket.",
        "(Conditionally required) s3:GetObjectAcl",
        "Required if you want to display object owner information.",
        "Object operations and permissions",
        "CopyObject",
        "(Required) s3:PutObject",
        "Required to write the object to the destination bucket.",
    ))
    assert operations == {
        "CopyObject": {"section": "Object operations and permissions", "required": ["s3:PutObject"]},
        "ListObjectsV2": {"section": "Bucket operations and permissions", "required": ["s3:ListBucket"]},
    }


def test_an_either_row_keeps_both_actions_and_the_sentence_each_gives():
    operations = f.read_page(page(
        "Object operations and permissions",
        "GetObject",
        "(Required) Either s3:GetObject or s3:GetObjectVersion",
        "s3:GetObject – Required if you want to get an object without specifying versionId in the request.",
        "s3:GetObjectVersion – Required if you want to get a specific version of an object by specifying "
        "versionId in the request.",
    ))
    assert operations["GetObject"]["either"] == {
        "actions": ["s3:GetObject", "s3:GetObjectVersion"],
        "sentences": {
            "s3:GetObject": "Required if you want to get an object without specifying versionId in the request.",
            "s3:GetObjectVersion": "Required if you want to get a specific version of an object by specifying "
                                   "versionId in the request.",
        },
    }


def test_an_either_row_missing_a_sentence_is_refused():
    with pytest.raises(ValueError):
        f.read_page(page("GetObject", "(Required) Either s3:GetObject or s3:GetObjectVersion",
                         "s3:GetObject - Required if you want to get an object without specifying versionId."))


def test_a_level_tells_two_operations_of_one_name_apart_and_other_notes_are_dropped():
    operations = f.read_page(page(
        "GetPublicAccessBlock (Bucket-level)",
        "(Required) s3:GetBucketPublicAccessBlock",
        "PutPublicAccessBlock (Account-level)",
        "(Required) s3:PutAccountPublicAccessBlock",
        # A note may run onto the next line; the rows under it are its operation's, not the one before.
        "DeleteBucketLifecycle",
        "(Required) s3:PutLifecycleConfiguration",
        "DeleteBucketMetadataConfiguration (V2 API operation. The IAM",
        "policy action name is the same for the V1 and V2 API operations.)",
        "(Required) s3:DeleteBucketMetadataTableConfiguration",
    ))
    assert operations["GetPublicAccessBlock (Bucket-level)"]["required"] == ["s3:GetBucketPublicAccessBlock"]
    assert operations["PutPublicAccessBlock (Account-level)"]["required"] == ["s3:PutAccountPublicAccessBlock"]
    assert operations["DeleteBucketLifecycle"]["required"] == ["s3:PutLifecycleConfiguration"]
    assert operations["DeleteBucketMetadataConfiguration"]["required"] == ["s3:DeleteBucketMetadataTableConfiguration"]


def test_a_row_several_operations_share_is_kept_whole_under_its_heading():
    operations = f.read_page(page(
        "CreateStorageLensGroup",
        "(Required) s3:CreateStorageLensGroup",
        "CreateStorageLensGroup, TagResource",
        "(Required) s3:CreateStorageLensGroup,",
        "s3:TagResource",
        "ListStorageLensGroups",
        "(Required) s3:ListStorageLensGroups",
        "(Required) s3:ListStorageLensGroups",
    ))
    assert operations["CreateStorageLensGroup"]["required"] == ["s3:CreateStorageLensGroup"]
    assert operations["CreateStorageLensGroup, TagResource"]["required"] == ["s3:CreateStorageLensGroup",
                                                                             "s3:TagResource"]
    assert operations["ListStorageLensGroups"]["required"] == ["s3:ListStorageLensGroups"]


def test_a_required_row_running_over_lines_keeps_every_action_other_services_included():
    operations = f.read_page(page(
        "UpdateObjectEncryption",
        "(Required) s3:UpdateObjectEncryption,",
        "kms:Encrypt, kms:Decrypt, kms:GenerateDataKey,",
        "kms:ReEncrypt*",
        "Required if you want to change encrypted objects between server-side encryption types.",
    ))
    assert operations["UpdateObjectEncryption"]["required"] == [
        "s3:UpdateObjectEncryption", "kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey", "kms:ReEncrypt*"]


def test_a_required_row_ending_in_a_comma_with_no_action_after_it_is_refused():
    with pytest.raises(ValueError):
        f.read_page(page("CreateStorageLensGroup, TagResource", "(Required) s3:CreateStorageLensGroup,",
                         "Required to create a Storage Lens group with tags."))


def test_the_source_names_its_fetcher():
    assert f.build(page(*[row for index in range(f.MINIMUM_OPERATIONS) for row in (
        f"GetThing{index}", "(Required) Either s3:GetObject or s3:GetObjectVersion",
        "s3:GetObject - Required if you want to get an object without specifying versionId in the request.",
        "s3:GetObjectVersion - Required if you want to get a specific version of an object by specifying versionId "
        "in the request.")]))["_source"]["fetcher"] == "tools/aws/fetch_s3_required_actions.py"


def test_a_page_giving_too_few_operations_has_changed_its_layout():
    with pytest.raises(ValueError):
        f.build(page("ListObjectsV2", "(Required) s3:ListBucket"))
