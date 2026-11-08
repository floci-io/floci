"""Tests for regen_iam_shapes: what this generator does that the shared one does not.

The driver, the staleness check and the file contract every constraints generator must hold are
`request_shapes.main`'s and are tested in test_request_shapes. What is IAM's own are its two
reductions, its one reorder and its one quoted pattern: an operation vendored without the model's
required members because it answers those for itself in AWS's Query wording, one vendored without a
member it answers in a wording of its own, a shape whose enum is vendored in the order AWS lists it
inside a validation message rather than the order botocore declares, and a member whose message
quotes a pattern AWS gives for it rather than the one its shape declares.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

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


def test_a_named_enum_is_vendored_in_the_order_aws_lists_it():
    model = {
        "operations": {},
        "shapes": {
            "statusType": {"type": "string", "enum": ["Active", "Expired", "Inactive"]},
            "nameType": {"type": "string", "min": 1, "max": 128},
        },
    }

    reordered = r.enum_order(model, {"statusType": ("Expired", "Active", "Inactive")})

    assert reordered["shapes"]["statusType"] == {
        "type": "string", "enum": ["Expired", "Active", "Inactive"]}
    # Every other shape is left as it was, and so is the model handed in: the generator reads this
    # model again for the staleness check, so a reorder in place would compare it against itself.
    assert reordered["shapes"]["nameType"] == model["shapes"]["nameType"]
    assert model["shapes"]["statusType"]["enum"] == ["Active", "Expired", "Inactive"]
    assert r.enum_order(model, {}) == model


@pytest.mark.parametrize("order", [
    ("Expired", "Active", "Inactive", "Enabled"),
    ("Expired", "Active"),
    ("Expired", "Active", "Active"),
])
def test_an_order_that_is_not_a_reordering_of_the_models_values_stops_the_run(order):
    # Only the order is read from the capture; the values are the model's. So a capture naming a
    # value the model does not declare, dropping one it does, or repeating one is a stale capture
    # rather than a new set, and vendoring it would hold clients to an enum AWS never published.
    model = {"operations": {}, "shapes": {
        "statusType": {"type": "string", "enum": ["Active", "Expired", "Inactive"]}}}

    with pytest.raises(SystemExit):
        r.enum_order(model, {"statusType": order})


def test_the_vendored_file_carries_the_listed_enum_orders():
    # What says the reorder is wired into the generation rather than only tested on its own.
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))

    for name, order in r.ENUM_ORDER.items():
        assert doc["shapes"][name]["enum"] == list(order), name


def test_a_member_whose_message_quotes_another_pattern_carries_a_shape_of_its_own():
    model = {
        "operations": {"Link": {"input": {"shape": "LinkRequest"}}},
        "shapes": {
            "LinkRequest": {"type": "structure", "required": ["Principal"], "members": {
                "Principal": {"shape": "nameType"}, "Suffix": {"shape": "nameType"},
            }},
            "nameType": {"type": "string", "min": 1, "max": 128, "pattern": "[a-z,]+"},
        },
    }

    quoted = r.quoted_patterns(model, {("Link", "Principal"): ("nameType", "[a-z,]+", "[a-z]*")})

    assert quoted["shapes"]["principalType"] == {
        "type": "string", "min": 1, "max": 128, "pattern": "[a-z,]+", "quotedPattern": "[a-z]*"}
    assert quoted["shapes"]["LinkRequest"]["members"]["Principal"] == {"shape": "principalType"}
    # The bounds and the pattern matched on are the shape's own, and the member that shares that
    # shape is left on it: it is one member's message that differs, not the shape's rules.
    assert quoted["shapes"]["LinkRequest"]["members"]["Suffix"] == {"shape": "nameType"}
    assert quoted["shapes"]["nameType"] == model["shapes"]["nameType"]
    assert "quotedPattern" not in quoted["shapes"]["nameType"]
    assert model["shapes"]["LinkRequest"]["members"]["Principal"] == {"shape": "nameType"}
    assert r.quoted_patterns(model, {}) == model


@pytest.mark.parametrize("entry", [
    ("otherType", "[a-z,]+", "[a-z]*"),
    ("nameType", "[a-z]+", "[a-z]*"),
])
def test_a_quoted_pattern_recorded_against_another_shape_or_pattern_stops_the_run(entry):
    # The entry names the shape the member resolves to and the pattern that shape declares, so a
    # model that moves the member or rewrites the pattern must stop the run: the captured text was
    # read against what the model said then, and quoting it against something else is a guess.
    model = {
        "operations": {"Link": {"input": {"shape": "LinkRequest"}}},
        "shapes": {
            "LinkRequest": {"type": "structure", "members": {"Principal": {"shape": "nameType"}}},
            "nameType": {"type": "string", "pattern": "[a-z,]+"},
            "otherType": {"type": "string", "pattern": "[a-z,]+"},
        },
    }

    with pytest.raises(SystemExit):
        r.quoted_patterns(model, {("Link", "Principal"): entry})


def test_a_quoted_pattern_for_a_member_this_generator_drops_stops_the_run():
    # A member put back on the by-hand list, or dropped by the model, is answered somewhere else:
    # quoting a pattern for one the vendored file does not carry would be quoting nothing.
    model = {
        "operations": {"Link": {"input": {"shape": "LinkRequest"}}},
        "shapes": {
            "LinkRequest": {"type": "structure", "members": {"Suffix": {"shape": "nameType"}}},
            "nameType": {"type": "string", "pattern": "[a-z,]+"},
        },
    }

    with pytest.raises(SystemExit):
        r.quoted_patterns(model, {("Link", "Principal"): ("nameType", "[a-z,]+", "[a-z]*")})


def test_the_vendored_file_carries_the_listed_quoted_patterns():
    # What says the quoted text is wired into the generation rather than only tested on its own,
    # and that the shape it was copied from keeps the message it had.
    doc = json.loads(r.OUTPUT.read_text(encoding="utf-8"))

    for (operation, member), (shape_name, declared, text) in r.QUOTED_PATTERNS.items():
        own = doc["shapes"][doc["operations"][operation]]["members"][member]
        assert own != shape_name, member
        assert doc["shapes"][own]["pattern"] == declared, member
        assert doc["shapes"][own]["quotedPattern"] == text, member
        assert "quotedPattern" not in doc["shapes"][shape_name], shape_name
