"""Tests for regen_iam_shapes: what this generator does that the shared one does not.

The driver, the staleness check and the file contract every constraints generator must hold are
`request_shapes.main`'s and are tested in test_request_shapes. What is IAM's own are its two
reductions: an operation vendored without the model's required members because it answers those for
itself in AWS's Query wording, and one vendored without a member it answers in a wording of its own.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import regen_iam_shapes as r


def test_every_paged_operation_is_held_to_the_model():
    # IamQueryHandler checks no MaxItems or Marker bound of its own, so an operation the paginator
    # table pages and this wave leaves out would take any limit and any marker length.
    paged = json.loads((r.OUTPUT.parent / "iam-paginators.json").read_text(encoding="utf-8"))["operations"]

    assert sorted(set(paged) - set(r.OPERATIONS)) == []


def test_an_operation_that_answers_required_members_itself_carries_none():
    model = {
        "operations": {"Upload": {"input": {"shape": "UploadRequest"}}},
        "shapes": {
            "UploadRequest": {"type": "structure", "required": ["Body"], "members": {
                "Body": {"shape": "bodyType"}, "Name": {"shape": "nameType"},
            }},
            "bodyType": {"type": "string", "min": 1},
            "nameType": {"type": "string", "min": 1, "max": 128},
        },
    }

    reduced = r.required_by_hand(model, ("Upload",))

    assert reduced["shapes"]["UploadRequest"] == {"type": "structure", "members": {
        "Body": {"shape": "bodyType"}, "Name": {"shape": "nameType"}}}
    # The members and their shapes stay: it is the required list alone that comes off.
    assert reduced["shapes"]["bodyType"] == model["shapes"]["bodyType"]
    assert r.required_by_hand(model, ()) == model


def test_the_vendored_file_carries_both_reductions():
    # What says each reduction is wired into the generation rather than only tested on its own.
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))

    for operation, members in r.MEMBERS_BY_HAND.items():
        shape = doc["shapes"][doc["operations"][operation]]
        assert not set(members) & set(shape["members"]), operation
    for operation in r.REQUIRED_BY_HAND:
        shape = doc["shapes"][doc["operations"][operation]]
        assert "required" not in shape, operation
        assert shape["members"], operation


def test_the_vendored_file_carries_the_listed_operations_and_no_others():
    # The list is what says which operations are read, so a file holding one it does not name, or
    # missing one it does, is a file that drifted from the list rather than from the model: the
    # staleness check answers the model. The two alias operations are the only ones deliberately
    # left out, their single member being answered in AWS's own wording.
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    left_out = {"CreateAccountAlias", "DeleteAccountAlias"}

    assert set(doc["operations"]) == set(r.OPERATIONS)
    assert left_out - set(r.OPERATIONS) == left_out
