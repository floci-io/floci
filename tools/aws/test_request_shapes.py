"""Tests for request_shapes, the generator behind regen_dynamodb_shapes and regen_sesv2_shapes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import gzip
import json
from pathlib import Path

import pytest

import regen_dynamodb_shapes
import regen_sesv2_shapes
import request_shapes as r


DYNAMODB_MODEL = {
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


SESV2_MODEL = {
    "operations": {
        "SendEmail": {"input": {"shape": "SendEmailRequest"}, "output": {"shape": "SendEmailResponse"}},
        "GetEmailIdentity": {"input": {"shape": "GetEmailIdentityRequest"}},
        "NoInput": {},
    },
    "shapes": {
        "SendEmailRequest": {"type": "structure", "members": {
            "FromEmailAddress": {"shape": "EmailAddress"},
            "ReplyToAddresses": {"shape": "EmailAddressList"},
            "Content": {"shape": "EmailContent"},
        }},
        "SendEmailResponse": {"type": "structure", "members": {"MessageId": {"shape": "OutboundMessageId"}}},
        "GetEmailIdentityRequest": {"type": "structure", "members": {
            "EmailIdentity": {"shape": "Identity", "location": "uri", "locationName": "EmailIdentity"},
            "NextToken": {"shape": "NextToken", "location": "querystring", "locationName": "NextToken"},
        }},
        "EmailAddress": {"type": "string"},
        "EmailAddressList": {"type": "list", "member": {"shape": "EmailAddress"}},
        "EmailContent": {"type": "structure", "members": {"Raw": {"shape": "RawMessage"}}},
        "RawMessage": {"type": "structure", "members": {"Data": {"shape": "RawMessageData"}}},
        "RawMessageData": {"type": "blob"},
        "OutboundMessageId": {"type": "string"},
        "Identity": {"type": "string", "min": 1},
        "NextToken": {"type": "string"},
    },
}


def test_keeps_only_input_shapes_and_their_types():
    doc = r.build(DYNAMODB_MODEL, "botocore test", "tools/aws/regen_dynamodb_shapes.py")

    assert doc["_source"] == {"generator": "tools/aws/regen_dynamodb_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"DescribeLimits": "DescribeLimitsInput", "Scan": "ScanInput"}
    assert "ScanOutput" not in doc["shapes"]
    assert "Integer" not in doc["shapes"]
    assert doc["shapes"]["ScanInput"] == {"type": "structure", "members": {
        "TableName": "TableName", "AttributesToGet": "AttributeNameList", "ExclusiveStartKey": "Key"}}
    assert doc["shapes"]["TableName"] == {"type": "string"}
    assert doc["shapes"]["AttributeNameList"] == {"type": "list", "member": "AttributeName"}
    assert doc["shapes"]["Key"] == {"type": "map", "value": "AttributeValue"}


def test_keeps_only_body_members_of_input_shapes():
    doc = r.build(SESV2_MODEL, "botocore test", "tools/aws/regen_sesv2_shapes.py")

    assert doc["_source"] == {"generator": "tools/aws/regen_sesv2_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"GetEmailIdentity": "GetEmailIdentityRequest", "SendEmail": "SendEmailRequest"}
    assert "SendEmailResponse" not in doc["shapes"]
    assert "OutboundMessageId" not in doc["shapes"]
    assert doc["shapes"]["SendEmailRequest"] == {"type": "structure", "members": {
        "FromEmailAddress": "EmailAddress", "ReplyToAddresses": "EmailAddressList", "Content": "EmailContent"}}
    assert doc["shapes"]["EmailAddressList"] == {"type": "list", "member": "EmailAddress"}
    assert doc["shapes"]["RawMessageData"] == {"type": "blob"}


def test_drops_uri_and_query_members():
    doc = r.build(SESV2_MODEL, "botocore test", "tools/aws/regen_sesv2_shapes.py")

    assert doc["shapes"]["GetEmailIdentityRequest"] == {"type": "structure", "members": {}}
    assert "Identity" not in doc["shapes"]
    assert "NextToken" not in doc["shapes"]


def _main(output: Path) -> int:
    return r.main(["--check", "--output", str(output)], script=r.__file__, model=Path("svc/service-2.json"),
                  output=output, description="test")


def test_check_ignores_provenance(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    output.write_text(r.render(r.build(DYNAMODB_MODEL, "botocore 0.0.1 (local/aws/botocore)",
                                       "tools/aws/regen_test_shapes.py")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "--botocore-data /elsewhere"))
    monkeypatch.setattr(r, "load_model", lambda data, model: DYNAMODB_MODEL)

    assert _main(output) == 0


def test_check_reports_changed_shapes(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    changed = {**DYNAMODB_MODEL, "shapes": {**DYNAMODB_MODEL["shapes"], "TableName": {"type": "integer"}}}
    output.write_text(r.render(r.build(changed, "botocore test", "tools/aws/regen_test_shapes.py")),
                      encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model: DYNAMODB_MODEL)

    assert _main(output) == 1


def test_output_is_stable():
    first = r.render(r.build(DYNAMODB_MODEL, "botocore test", "tools/aws/regen_dynamodb_shapes.py"))
    second = r.render(r.build(DYNAMODB_MODEL, "botocore test", "tools/aws/regen_dynamodb_shapes.py"))

    assert first == second
    assert first.endswith("\n")


def test_generator_name_is_the_scripts_path_from_the_repository_root():
    assert r.generator_name(regen_dynamodb_shapes.__file__) == "tools/aws/regen_dynamodb_shapes.py"
    assert r.generator_name(regen_sesv2_shapes.__file__) == "tools/aws/regen_sesv2_shapes.py"


def test_generator_name_outside_the_repository_is_the_file_name(tmp_path):
    assert r.generator_name(str(tmp_path / "regen_copy_shapes.py")) == "regen_copy_shapes.py"


def test_load_model_reads_a_gzipped_service_model(tmp_path):
    version = tmp_path / "svc" / "2020-01-01"
    version.mkdir(parents=True)
    with gzip.open(version / "service-2.json.gz", "wt", encoding="utf-8") as f:
        json.dump(SESV2_MODEL, f)

    assert r.load_model(tmp_path, Path("svc/2020-01-01")) == SESV2_MODEL


def test_load_model_without_a_service_model_exits(tmp_path):
    with pytest.raises(SystemExit):
        r.load_model(tmp_path, Path("svc/2020-01-01"))


@pytest.mark.parametrize("script, model, output", [
    (regen_dynamodb_shapes, "dynamodb/2012-08-10", "dynamodb-request-shapes.json"),
    (regen_sesv2_shapes, "sesv2/2019-09-27", "sesv2-request-shapes.json"),
])
def test_each_script_runs_the_shared_main_with_its_model_and_output(script, model, output, monkeypatch):
    calls = []
    monkeypatch.setattr(r, "main", lambda argv, **kwargs: calls.append((argv, kwargs)) or 0)

    assert script.main(["--check"]) == 0
    assert calls == [(["--check"], {
        "script": script.__file__,
        "model": Path(model),
        "output": r.REPO_ROOT / "src/main/resources/aws" / output,
        "description": script.__doc__,
    })]
