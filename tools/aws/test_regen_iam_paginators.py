"""Tests for regen_iam_paginators.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_paginators as r

MAX_ITEMS_DOC = "<p>If you do not include this parameter, the number of items defaults to 100.</p>"


def model(**operations: tuple[dict, dict]) -> dict:
    """A model whose operations map to (request members, response members), each member naming
    its shape."""
    shapes = {
        "maxItemsType": {"type": "integer", "min": 1, "max": 1000},
        "markerType": {"type": "string", "min": 1, "max": 320, "pattern": "[\\u0020-\\u00FF]+"},
        "booleanType": {"type": "boolean"},
        "listType": {"type": "list"},
    }
    ops = {}
    for name, (request, response) in operations.items():
        shapes[f"{name}Request"] = {"type": "structure", "members": request}
        shapes[f"{name}Response"] = {"type": "structure", "members": response}
        ops[name] = {"input": {"shape": f"{name}Request"}, "output": {"shape": f"{name}Response"}}
    return {"operations": ops, "shapes": shapes}


PAGED_REQUEST = {"Marker": {"shape": "markerType"},
                 "MaxItems": {"shape": "maxItemsType", "documentation": MAX_ITEMS_DOC}}


def paged_response(*lists: str) -> dict:
    return {**{key: {"shape": "listType"} for key in lists},
            "IsTruncated": {"shape": "booleanType"}, "Marker": {"shape": "markerType"}}


def paginator(*result_keys: str) -> dict:
    return {"input_token": "Marker", "output_token": "Marker", "limit_key": "MaxItems",
            "more_results": "IsTruncated",
            "result_key": result_keys[0] if len(result_keys) == 1 else list(result_keys)}


def test_a_paginator_names_its_tokens_lists_and_default_but_no_bounds():
    found = r.operations_from(model(ListRoles=(PAGED_REQUEST, paged_response("Roles"))),
                              {"ListRoles": paginator("Roles")})

    assert found == {"ListRoles": {
        "paginator": True, "inputToken": "Marker", "outputToken": "Marker", "limitKey": "MaxItems",
        "moreResults": "IsTruncated", "resultKeys": ["Roles"], "defaultItems": 100,
    }}


def test_several_result_keys_are_kept_in_the_paginators_order():
    found = r.operations_from(
        model(ListEntitiesForPolicy=(PAGED_REQUEST, paged_response("PolicyGroups", "PolicyUsers"))),
        {"ListEntitiesForPolicy": paginator("PolicyGroups", "PolicyUsers")})

    assert found["ListEntitiesForPolicy"]["resultKeys"] == ["PolicyGroups", "PolicyUsers"]


def test_an_operation_paged_without_a_paginator_is_read_from_its_shapes():
    # ListDelegationRequests spells the flag isTruncated, and the table keeps the model's spelling.
    response = {"DelegationRequests": {"shape": "listType"}, "Marker": {"shape": "markerType"},
                "isTruncated": {"shape": "booleanType"}}
    found = r.operations_from(model(ListDelegationRequests=(PAGED_REQUEST, response)), {})

    entry = found["ListDelegationRequests"]
    assert entry["paginator"] is False
    assert entry["moreResults"] == "isTruncated"
    assert entry["resultKeys"] == []


def test_a_request_without_max_items_has_no_limit():
    found = r.operations_from(model(ListPoliciesGrantingServiceAccess=(
        {"Marker": {"shape": "markerType"}}, paged_response("PoliciesGrantingServiceAccess"))), {})

    assert found["ListPoliciesGrantingServiceAccess"]["limitKey"] is None
    assert "defaultItems" not in found["ListPoliciesGrantingServiceAccess"]


def test_an_undocumented_default_is_null_rather_than_invented():
    request = {"Marker": {"shape": "markerType"}, "MaxItems": {"shape": "maxItemsType"}}
    found = r.operations_from(model(ListServiceSpecificCredentials=(request, paged_response("C"))), {})

    assert found["ListServiceSpecificCredentials"]["defaultItems"] is None


def test_an_operation_without_a_marker_is_left_out():
    assert r.operations_from(model(GetUser=({}, {"User": {"shape": "listType"}})), {}) == {}


def test_a_paginator_naming_a_member_the_shapes_lack_is_refused():
    with pytest.raises(ValueError, match="response has no Users"):
        r.operations_from(model(ListRoles=(PAGED_REQUEST, paged_response("Roles"))),
                          {"ListRoles": paginator("Users")})


def test_a_paginator_for_an_operation_without_a_marker_is_refused():
    with pytest.raises(ValueError, match="no Marker"):
        r.operations_from(model(ListRoles=({}, paged_response("Roles"))), {"ListRoles": paginator("Roles")})


def test_check_ignores_the_provenance_and_catches_a_changed_entry(tmp_path):
    directory = tmp_path.joinpath("data", *r.MODEL_DIR)
    directory.mkdir(parents=True)
    (directory / "service-2.json").write_text(
        json.dumps(model(ListRoles=(PAGED_REQUEST, paged_response("Roles")))), encoding="utf-8")
    (directory / "paginators-1.json").write_text(
        json.dumps({"pagination": {"ListRoles": paginator("Roles")}}), encoding="utf-8")
    data = str(tmp_path / "data")
    output = tmp_path / "iam-paginators.json"

    assert r.main(["--botocore-data", data, "--output", str(output)]) == 0
    assert r.main(["--check", "--botocore-data", data, "--output", str(output)]) == 0

    stale = json.loads(output.read_text(encoding="utf-8"))
    stale["_source"]["botocore"] = "another label"
    output.write_text(json.dumps(stale), encoding="utf-8")
    assert r.main(["--check", "--botocore-data", data, "--output", str(output)]) == 0

    stale["operations"]["ListRoles"]["defaultItems"] = 10
    output.write_text(json.dumps(stale), encoding="utf-8")
    assert r.main(["--check", "--botocore-data", data, "--output", str(output)]) == 1
