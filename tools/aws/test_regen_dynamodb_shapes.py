"""Tests for regen_dynamodb_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import regen_dynamodb_shapes as r


MODEL = {
    "operations": {
        "Scan": {"input": {"shape": "ScanInput"}, "output": {"shape": "ScanOutput"}},
        "DescribeLimits": {"input": {"shape": "DescribeLimitsInput"}},
        "NoInput": {},
    },
    "shapes": {
        "ScanInput": {"type": "structure", "members": {
            "TableName": {"shape": "TableName"},
            "AttributesToGet": {"shape": "AttributeNameList"},
            "ExclusiveStartKey": {"shape": "Key"},
        }},
        "ScanOutput": {"type": "structure", "members": {"Count": {"shape": "Integer"}}},
        "DescribeLimitsInput": {"type": "structure", "members": {}},
        "TableName": {"type": "string", "min": 3},
        "AttributeNameList": {"type": "list", "member": {"shape": "AttributeName"}},
        "AttributeName": {"type": "string"},
        "Key": {"type": "map", "key": {"shape": "AttributeName"}, "value": {"shape": "AttributeValue"}},
        "AttributeValue": {"type": "structure", "members": {"S": {"shape": "AttributeName"}}},
        "Integer": {"type": "integer"},
    },
}


def test_keeps_only_input_shapes_and_their_types():
    doc = r.build(MODEL, "botocore test")

    assert doc["source"] == "botocore test"
    assert doc["operations"] == {"DescribeLimits": "DescribeLimitsInput", "Scan": "ScanInput"}
    assert "ScanOutput" not in doc["shapes"]
    assert "Integer" not in doc["shapes"]
    assert doc["shapes"]["ScanInput"] == {"type": "structure", "members": {
        "TableName": "TableName", "AttributesToGet": "AttributeNameList", "ExclusiveStartKey": "Key"}}
    assert doc["shapes"]["TableName"] == {"type": "string"}
    assert doc["shapes"]["AttributeNameList"] == {"type": "list", "member": "AttributeName"}
    assert doc["shapes"]["Key"] == {"type": "map", "value": "AttributeValue"}


def test_output_is_stable():
    first = r.render(r.build(MODEL, "botocore test"))
    second = r.render(r.build(MODEL, "botocore test"))

    assert first == second
    assert first.endswith("\n")
