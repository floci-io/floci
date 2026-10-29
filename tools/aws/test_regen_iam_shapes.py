"""Tests for regen_iam_shapes: what this generator does that the shared one does not.

The driver, the staleness check and the file contract every constraints generator must hold are
`request_shapes.main`'s and are tested in test_request_shapes. What is IAM's own is the paging
reduction: an operation vendored for its `MaxItems` and `Marker` alone, with the rest of its
request left to the reading it has today.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import regen_iam_shapes as r
from request_shapes import build_constraints


def test_every_paged_operation_is_held_to_the_model():
    # IamQueryHandler checks no MaxItems or Marker bound of its own, so an operation the paginator
    # table pages and this wave leaves out would take any limit and any marker length.
    paged = json.loads((r.OUTPUT.parent / "iam-paginators.json").read_text(encoding="utf-8"))["operations"]

    assert sorted(set(paged) - set(r.VENDORED)) == []


def test_the_vendored_file_carries_every_paged_request_reduced():
    # What says the reduction is wired into the generation rather than only tested on its own: a
    # driver that never ran it would vendor these requests whole, with the members this wave leaves
    # to the reading they have today.
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))

    for operation in r.PAGING_ONLY:
        members = doc["shapes"][doc["operations"][operation]]["members"]
        # `ListPoliciesGrantingServiceAccess` pages on a `Marker` alone, so what holds for all of
        # them is that nothing but a paging member is left, and that one of them is.
        assert members and set(members) <= set(r.PAGING_MEMBERS), operation


def test_a_paging_only_operation_keeps_its_paging_members_alone():
    model = {
        "operations": {"ListThings": {"input": {"shape": "ListThingsRequest"}}},
        "shapes": {
            "ListThingsRequest": {"type": "structure", "required": ["JobId"], "members": {
                "JobId": {"shape": "jobIDType"}, "Marker": {"shape": "markerType"},
                "MaxItems": {"shape": "maxItemsType"},
            }},
            "jobIDType": {"type": "string", "min": 36, "max": 36},
            "markerType": {"type": "string", "min": 1, "max": 320},
            "maxItemsType": {"type": "integer", "min": 1, "max": 1000},
        },
    }

    # The composition the generator hands the shared driver: the reduced model and the full list.
    doc = build_constraints(r.paging_only(model, ("ListThings",)), ("ListThings",), "botocore test",
                            "tools/aws/regen_iam_shapes.py")

    assert doc["shapes"]["ListThingsRequest"] == {
        "type": "structure", "members": {"Marker": "markerType", "MaxItems": "maxItemsType"}}
    assert "jobIDType" not in doc["shapes"]


def test_the_paging_reduction_leaves_every_other_operation_whole():
    model = {
        "operations": {"GetThing": {"input": {"shape": "GetThingRequest"}}},
        "shapes": {
            "GetThingRequest": {"type": "structure", "members": {"JobId": {"shape": "jobIDType"}}},
            "jobIDType": {"type": "string", "min": 36, "max": 36},
        },
    }

    assert r.paging_only(model, ()) == model
