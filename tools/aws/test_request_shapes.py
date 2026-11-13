"""Tests for request_shapes, the generator behind regen_dynamodb_shapes, regen_sesv2_shapes, regen_apigateway_shapes,
regen_ecs_shapes, regen_organizations_shapes and regen_emr_serverless_shapes, and of the constraints
regen_resource_groups_shapes, regen_ecr_shapes, regen_iam_shapes and regen_sts_shapes vendor.

Each generator names a model, an output and, for the constraints flavour, its operations, and hands
the rest here, so what any one of them does differently is a constant and what they all do is
tested once.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import gzip
import json
import re
from copy import deepcopy
from pathlib import Path

import pytest

import regen_apigateway_documentation_shapes
import regen_apigateway_shapes
import regen_athena_shapes
import regen_cur_shapes
import regen_dynamodb_shapes
import regen_ecr_shapes
import regen_ecs_shapes
import regen_emr_serverless_shapes
import regen_iam_shapes
import regen_cloudfront_keyvaluestore_shapes
import regen_kinesis_shapes
import regen_lambda_microvm_shapes
import regen_organizations_shapes
import regen_payment_cryptography_data_shapes
import regen_payment_cryptography_shapes
import regen_logs_delivery_shapes
import regen_resource_groups_shapes
import regen_rekognition_shapes
import regen_securityhub_shapes
import regen_sesv2_shapes
import regen_sts_shapes
import request_shapes as r


DYNAMODB_MODEL = {
    "operations": {
        "Scan": {"input": {"shape": "ScanInput"}, "output": {"shape": "ScanOutput"}},
        "DescribeLimits": {"input": {"shape": "DescribeLimitsInput"}},
        "NoInput": {},
    },
    "shapes": {
        "ScanInput": {"type": "structure", "required": ["TableName"], "members": {
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


def test_keeps_required_members_only_when_asked():
    doc = r.build(DYNAMODB_MODEL, "botocore test", "tools/aws/regen_dynamodb_shapes.py", required=True)

    assert doc["shapes"]["ScanInput"]["required"] == ["TableName"]
    assert "required" not in doc["shapes"]["DescribeLimitsInput"]


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


def test_keeps_output_shapes_when_asked():
    doc = r.build(SESV2_MODEL, "botocore test", "tools/aws/regen_emr_serverless_shapes.py", outputs=True)

    assert doc["operations"] == {"GetEmailIdentity": "GetEmailIdentityRequest", "SendEmail": "SendEmailRequest"}
    assert doc["shapes"]["SendEmailResponse"] == {"type": "structure", "members": {"MessageId": "OutboundMessageId"}}
    assert doc["shapes"]["OutboundMessageId"] == {"type": "string"}
    assert doc["shapes"]["GetEmailIdentityRequest"] == {"type": "structure", "members": {}}


def test_emr_serverless_shapes_keep_the_output_shapes(monkeypatch):
    calls = []
    monkeypatch.setattr(r, "main", lambda argv, **kwargs: calls.append((argv, kwargs)) or 0)

    assert regen_emr_serverless_shapes.main(["--check"]) == 0
    assert calls == [(["--check"], {
        "script": regen_emr_serverless_shapes.__file__,
        "model": Path("emr-serverless/2021-07-13"),
        "output": r.REPO_ROOT / "src/main/resources/aws/emr-serverless-shapes.json",
        "description": regen_emr_serverless_shapes.__doc__,
        "outputs": True,
    })]


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
    assert r.generator_name(regen_apigateway_shapes.__file__) == "tools/aws/regen_apigateway_shapes.py"
    assert r.generator_name(regen_ecs_shapes.__file__) == "tools/aws/regen_ecs_shapes.py"
    assert r.generator_name(regen_organizations_shapes.__file__) == "tools/aws/regen_organizations_shapes.py"


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


@pytest.mark.parametrize("script, model, output, options", [
    (regen_dynamodb_shapes, "dynamodb/2012-08-10", "dynamodb-request-shapes.json", {"required": True}),
    (regen_sesv2_shapes, "sesv2/2019-09-27", "sesv2-request-shapes.json", {}),
    (regen_apigateway_shapes, "apigateway/2015-07-09", "apigateway-request-shapes.json", {}),
    (regen_ecs_shapes, "ecs/2014-11-13", "ecs-request-shapes.json", {}),
    (regen_organizations_shapes, "organizations/2016-11-28", "organizations-request-shapes.json", {}),
])
def test_each_script_runs_the_shared_main_with_its_model_and_output(script, model, output, options, monkeypatch):
    calls = []
    monkeypatch.setattr(r, "main", lambda argv, **kwargs: calls.append((argv, kwargs)) or 0)

    assert script.main(["--check"]) == 0
    assert calls == [(["--check"], {
        "script": script.__file__,
        "model": Path(model),
        "output": r.REPO_ROOT / "src/main/resources/aws" / output,
        "description": script.__doc__,
        **options,
    })]


CONSTRAINED_MODEL = {
    "operations": {
        "Put": {"input": {"shape": "PutRequest"}, "output": {"shape": "PutResponse"}},
        "Unused": {"input": {"shape": "UnusedRequest"}},
    },
    "shapes": {
        "PutRequest": {"type": "structure", "required": ["name"], "members": {
            "name": {"shape": "Name", "documentation": "dropped"},
            "rules": {"shape": "RuleList"},
            "tags": {"shape": "TagMap"},
            "id": {"shape": "Name", "location": "uri", "locationName": "Id"},
        }},
        "PutResponse": {"type": "structure", "members": {}},
        "UnusedRequest": {"type": "structure", "members": {"other": {"shape": "Other"}}},
        "Name": {"type": "string", "min": 1, "max": 5, "pattern": "[a-z]+", "documentation": "dropped"},
        "RuleList": {"type": "list", "min": 0, "max": 25, "member": {"shape": "Kind"}},
        "Kind": {"type": "string", "enum": ["A", "B"]},
        "TagMap": {"type": "map", "key": {"shape": "Name"}, "value": {"shape": "Kind"}},
        "Other": {"type": "string"},
    },
}


@pytest.mark.parametrize("script, model_path", [
    (regen_athena_shapes, "athena/2017-05-18"),
    (regen_kinesis_shapes, "kinesis/2013-12-02"),
    (regen_rekognition_shapes, "rekognition/2016-06-27"),
])
def test_generators_keep_constraints_for_every_operation_and_detect_new_operations(script, model_path, tmp_path, monkeypatch):
    output = tmp_path / "request-shapes.json"
    model = deepcopy(CONSTRAINED_MODEL)
    model["shapes"]["Image"] = {"type": "structure", "members": {"Bytes": {"shape": "Blob"}}}
    model["shapes"]["Blob"] = {"type": "blob"}
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model_path: deepcopy(model))
    args = ["--output", str(output)]

    assert script.MODEL == Path(model_path)
    assert script.main(args) == 0
    doc = json.loads(output.read_text())
    assert doc["operations"] == {"Put": "PutRequest", "Unused": "UnusedRequest"}
    assert doc["shapes"]["Name"]["min"] == 1
    assert doc["shapes"]["Name"]["pattern"] == "[a-z]+"
    assert doc["shapes"]["PutRequest"]["required"] == ["name"]
    assert doc["shapes"]["Kind"]["enum"] == ["A", "B"]
    assert doc["shapes"]["RuleList"]["max"] == 25
    assert doc["shapes"]["TagMap"]["key"] == "Name"
    assert script.main(["--check", *args]) == 0

    changed = {**model, "operations": {**model["operations"],
                                                "NewOperation": {"input": {"shape": "PutRequest"}}}}
    monkeypatch.setattr(r, "load_model", lambda data, model_path: deepcopy(changed))
    assert script.main(["--check", *args]) == 1
    assert script.main(args) == 0
    assert "NewOperation" in json.loads(output.read_text())["operations"]


def test_build_constraints_keeps_the_named_operations_with_their_constraints():
    doc = r.build_constraints(CONSTRAINED_MODEL, ("Put",), "botocore test", "tools/aws/regen_x_shapes.py")

    assert doc["_source"] == {"generator": "tools/aws/regen_x_shapes.py", "botocore": "botocore test"}
    assert doc["operations"] == {"Put": "PutRequest"}
    assert set(doc["shapes"]) == {"PutRequest", "Name", "RuleList", "Kind", "TagMap"}
    # Members bound elsewhere than the body stay: the JSON protocols send every member in the body.
    assert doc["shapes"]["PutRequest"] == {"type": "structure", "required": ["name"], "members": {
        "name": "Name", "rules": "RuleList", "tags": "TagMap", "id": "Name"}}
    assert doc["shapes"]["Name"] == {"type": "string", "min": 1, "max": 5, "pattern": "[a-z]+"}
    assert doc["shapes"]["RuleList"] == {"type": "list", "min": 0, "max": 25, "member": "Kind"}
    assert doc["shapes"]["Kind"] == {"type": "string", "enum": ["A", "B"]}
    assert doc["shapes"]["TagMap"] == {"type": "map", "key": "Name", "value": "Kind"}


def test_build_constraints_refuses_an_operation_the_model_does_not_name():
    # A misspelled name in a generator's OPERATIONS fails the generation rather than vendoring a
    # file quietly missing that operation, which --check would then call up to date.
    with pytest.raises(KeyError):
        r.build_constraints(CONSTRAINED_MODEL, ("Puut",), "botocore test", "tools/aws/regen_x_shapes.py")


def _constraints_main(output: Path, *argv: str, **extra) -> int:
    return r.main(list(argv) or ["--check", "--output", str(output)], script=r.__file__,
                  model=Path("svc/service-2.json"), output=output, description="test",
                  operations=("Put",), **extra)


def test_constraints_check_ignores_provenance(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    output.write_text(r.render(r.build_constraints(CONSTRAINED_MODEL, ("Put",),
                                                   "botocore 0.0.1 (local/aws/botocore)",
                                                   "tools/aws/regen_x_shapes.py")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "--botocore-data /elsewhere"))
    monkeypatch.setattr(r, "load_model", lambda data, model: CONSTRAINED_MODEL)

    assert _constraints_main(output) == 0


def test_constraints_check_reports_a_changed_constraint(tmp_path, monkeypatch):
    output = tmp_path / "shapes.json"
    changed = {**CONSTRAINED_MODEL,
               "shapes": {**CONSTRAINED_MODEL["shapes"], "Name": {"type": "string", "min": 1, "max": 9}}}
    output.write_text(r.render(r.build_constraints(changed, ("Put",), "botocore test",
                                                   "tools/aws/regen_x_shapes.py")), encoding="utf-8")
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model: CONSTRAINED_MODEL)

    assert _constraints_main(output) == 1


def test_main_builds_from_the_prepared_model_and_checks_against_it(tmp_path, monkeypatch):
    # The one thing a generator may do to the model before the build, which IAM hands the driver to
    # vendor a request without the members it answers for itself. Dropping the callback, or running it
    # after the build, would vendor the model as loaded, so the file is read both ways here: it
    # carries what `prepare` left, and the staleness check calls that file up to date only when it
    # prepares the model the same way.
    output = tmp_path / "shapes.json"
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, model: CONSTRAINED_MODEL)
    reduced = {**CONSTRAINED_MODEL, "shapes": {
        **CONSTRAINED_MODEL["shapes"],
        "PutRequest": {"type": "structure", "members": {"rules": {"shape": "RuleList"}}}}}

    assert _constraints_main(output, "--output", str(output), prepare=lambda model: reduced) == 0

    doc = json.loads(output.read_text(encoding="utf-8"))
    assert doc["shapes"]["PutRequest"] == {"type": "structure", "members": {"rules": "RuleList"}}
    # The members the reduction dropped take their shapes with them.
    assert "Name" not in doc["shapes"]
    assert _constraints_main(output, "--check", "--output", str(output),
                             prepare=lambda model: reduced) == 0
    assert _constraints_main(output, "--check", "--output", str(output)) == 1


def test_build_constraints_output_is_stable():
    first = r.render(r.build_constraints(CONSTRAINED_MODEL, ("Put",), "botocore test", "tools/aws/regen_x_shapes.py"))
    second = r.render(r.build_constraints(CONSTRAINED_MODEL, ("Put",), "botocore test", "tools/aws/regen_x_shapes.py"))

    assert first == second
    assert first.endswith("\n")


@pytest.mark.parametrize("script, model, output, extra", [
    (regen_apigateway_documentation_shapes, "apigateway/2015-07-09",
     "apigateway-documentation-request-shapes.json", {}),
    # IAM vendors some operations in part, so it hands the driver its reductions as well as the
    # list: the required members an operation answers for itself and the members it answers in a
    # wording of its own.
    (regen_iam_shapes, "iam/2010-05-08", "iam-request-shapes.json",
     {"prepare": regen_iam_shapes.prepare}),
    (regen_sts_shapes, "sts/2011-06-15", "sts-request-shapes.json", {}),
    (regen_securityhub_shapes, "securityhub/2018-10-26", "securityhub-request-shapes.json",
     {"prepare": regen_securityhub_shapes.prepare}),
])
def test_each_constraints_script_runs_the_shared_main_with_its_operations(script, model, output, extra,
                                                                         monkeypatch):
    calls = []
    monkeypatch.setattr(r, "main", lambda argv, **kwargs: calls.append((argv, kwargs)) or 0)

    assert script.main(["--check"]) == 0
    assert calls == [(["--check"], {
        "script": script.__file__,
        "model": Path(model),
        "output": r.REPO_ROOT / "src/main/resources/aws" / output,
        "description": script.__doc__,
        "operations": vendored(script),
        **extra,
    })]


@pytest.mark.parametrize("script, model, output", [
    (regen_payment_cryptography_shapes, "payment-cryptography/2021-09-14",
     "payment-cryptography-request-shapes.json"),
    (regen_payment_cryptography_data_shapes, "payment-cryptography-data/2022-02-03",
     "payment-cryptography-data-request-shapes.json"),
    (regen_cloudfront_keyvaluestore_shapes, "cloudfront-keyvaluestore/2022-07-26",
     "cloudfront-keyvaluestore-request-shapes.json"),
])
def test_each_payment_cryptography_script_runs_the_shared_main_with_every_operation(script, model, output,
                                                                                   monkeypatch):
    # The generator names no operation, so it asks the driver for the model's whole set: an
    # operation the service serves later is covered without anyone adding it to a list.
    calls = []
    monkeypatch.setattr(r, "main", lambda argv, **kwargs: calls.append((argv, kwargs)) or 0)

    assert script.main(["--check"]) == 0
    assert calls == [(["--check"], {
        "script": script.__file__,
        "model": Path(model),
        "output": r.REPO_ROOT / "src/main/resources/aws" / output,
        "description": script.__doc__ or "",
        "constraints": True,
        "prepare": r.widen_partition_and_region,
    })]


def vendored(script) -> tuple[str, ...]:
    """The operations a generator's file carries, which is `OPERATIONS` unless it names more."""
    return getattr(script, "VENDORED", None) or script.OPERATIONS


@pytest.mark.parametrize("script", [
    regen_apigateway_documentation_shapes, regen_ecr_shapes, regen_iam_shapes, regen_logs_delivery_shapes,
    regen_resource_groups_shapes, regen_sts_shapes,
    regen_securityhub_shapes,
    regen_kinesis_shapes,
    regen_rekognition_shapes,
    regen_athena_shapes,
    regen_cur_shapes,
    regen_lambda_microvm_shapes,
])
def test_each_vendored_constraints_file_carries_its_operations_and_resolves_its_shapes(script):
    doc = json.loads(script.OUTPUT.read_text(encoding="utf-8"))

    # A generator that names no operation carries every one the model names, so the
    # operations are only compared for one that names them.
    if hasattr(script, "OPERATIONS") or hasattr(script, "VENDORED"):
        assert sorted(doc["operations"]) == sorted(vendored(script))
    assert_shapes_resolve(doc)


@pytest.mark.parametrize("script", [
    regen_payment_cryptography_shapes, regen_payment_cryptography_data_shapes,
    regen_cloudfront_keyvaluestore_shapes,
])
def test_each_payment_cryptography_file_carries_every_operation_the_model_names(script):
    # The generator names no operation, so the file carries every operation the model names:
    # an operation the service serves later is checked without anyone adding it to a list.
    pytest.importorskip("botocore")
    try:
        data, _ = r.resolve_botocore_data(None)
    except SystemExit:
        pytest.skip("the botocore data cannot be read here")
    model = r.load_model(data, script.MODEL)

    doc = json.loads(script.OUTPUT.read_text(encoding="utf-8"))

    # An operation the model names without an input has no request to constrain.
    named = {name for name, operation in model["operations"].items() if "input" in operation}
    assert sorted(doc["operations"]) == sorted(named)
    assert_shapes_resolve(doc)


def test_main_leaves_out_an_operation_without_an_input(tmp_path, monkeypatch):
    # An operation the model names without an input has no request to constrain, and the
    # generator that asks for every operation leaves it out.
    model = {"operations": {"Put": {"input": {"shape": "PutRequest"}}, "Nothing": {}},
             "shapes": {"PutRequest": {"type": "structure", "members": {}}}}
    output = tmp_path / "shapes.json"
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "botocore test"))
    monkeypatch.setattr(r, "load_model", lambda data, path: model)

    assert r.main(["--output", str(output)], script=__file__, model=Path("x"), output=output,
                  description="test", constraints=True) == 0

    doc = json.loads(output.read_text())
    assert doc["operations"] == {"Put": "PutRequest"}


def assert_shapes_resolve(doc: dict) -> None:
    """Every operation's shape is in the file, and every shape's members resolve to shapes in it."""
    for operation, shape in doc["operations"].items():
        assert shape in doc["shapes"], operation
    for name, shape in doc["shapes"].items():
        named = list(shape.get("members", {}).values())
        named.extend(shape[side] for side in ("member", "key", "value") if side in shape)
        for child in named:
            assert child in doc["shapes"], f"{name} names {child}"


def test_securityhub_shapes_keep_modelled_findings_and_filter_constraints():
    doc = json.loads(regen_securityhub_shapes.OUTPUT.read_text(encoding="utf-8"))
    shapes = doc["shapes"]

    assert shapes["BatchImportFindingsRequestFindingList"]["min"] == 1
    assert shapes["BatchImportFindingsRequestFindingList"]["max"] == 100
    assert shapes["MaxResults"]["min"] == 1
    assert shapes["MaxResults"]["max"] == 100
    assert shapes["RatioScale"]["min"] == 0
    assert shapes["RatioScale"]["max"] == 100
    assert shapes["VerificationState"]["enum"] == ["UNKNOWN", "TRUE_POSITIVE", "FALSE_POSITIVE",
                                                     "BENIGN_POSITIVE"]
    assert shapes["RecordState"]["enum"] == ["ACTIVE", "ARCHIVED"]
    assert "CONTAINS" in shapes["StringFilterComparison"]["enum"]
    assert shapes["AwsSecurityFinding"]["members"]["ProductFields"] == "FieldMap"
    assert shapes["FindingProviderFields"]["members"]["RelatedFindings"] == "RelatedFindingList"
    assert shapes["RelatedFinding"]["required"] == ["ProductArn", "Id"]
    assert "Double" not in shapes


def test_rekognition_shapes_keep_constraints_and_leave_image_bytes_to_the_image_validator():
    doc = json.loads(regen_rekognition_shapes.OUTPUT.read_text(encoding="utf-8"))
    shapes = doc["shapes"]

    assert shapes["IndexFacesRequest"]["required"] == ["CollectionId", "Image"]
    assert shapes["PageSize"]["min"] == 0
    assert shapes["PageSize"]["max"] == 4096
    assert shapes["Percent"] == {"type": "float", "min": 0, "max": 100}
    assert shapes["Attributes"]["member"] == "Attribute"
    assert "FACE_OCCLUDED" in shapes["Attribute"]["enum"]
    assert shapes["TagKey"]["pattern"].startswith("^(?!aws:)")
    assert shapes["FaceIdList"]["max"] == 4096
    assert shapes["MaxFacesToIndex"] == {"type": "integer", "min": 1}
    assert shapes["Image"]["members"] == {"S3Object": "S3Object"}
    assert "ImageBlob" not in shapes


def test_kinesis_shapes_keep_tag_bounds_and_leave_wire_types_to_handlers():
    doc = json.loads(regen_kinesis_shapes.OUTPUT.read_text(encoding="utf-8"))
    shapes = doc["shapes"]

    assert doc["operations"]["RegisterStreamConsumer"] == "RegisterStreamConsumerInput"
    assert shapes["TagMap"]["min"] == 1
    assert shapes["TagMap"]["max"] == 200
    assert shapes["TagKey"]["min"] == 1
    assert shapes["TagKey"]["max"] == 128
    assert shapes["TagValue"]["max"] == 256
    assert shapes["TagKeyList"]["min"] == 1
    assert shapes["TagKeyList"]["max"] == 50
    assert "Data" not in shapes["PutRecordInput"]["members"]
    assert "Data" in shapes["PutRecordInput"]["required"]
    assert "Data" in shapes["PutRecordsRequestEntry"]["required"]
    assert "Timestamp" not in shapes["GetShardIteratorInput"]["members"]


def test_securityhub_shapes_keep_required_findings_and_resource_strings():
    doc = json.loads(regen_securityhub_shapes.OUTPUT.read_text(encoding="utf-8"))
    shapes = doc["shapes"]
    required = shapes["AwsSecurityFinding"]["required"]
    assert required == ["SchemaVersion", "Id", "ProductArn", "GeneratorId", "AwsAccountId",
                        "CreatedAt", "UpdatedAt", "Title", "Description", "Resources"]
    for field in required:
        assert field in shapes["AwsSecurityFinding"]["members"]
    assert shapes["Resource"]["required"] == ["Type", "Id"]
    assert shapes["Resource"]["members"] == {"Type": "NonEmptyString", "Id": "NonEmptyString"}
    assert shapes["ResourceList"]["member"] == "Resource"
    assert shapes["NonEmptyString"]["pattern"] == r".*\S.*"


def test_apigateway_documentation_shapes_keep_path_query_and_body_constraints():
    doc = json.loads(regen_apigateway_documentation_shapes.OUTPUT.read_text(encoding="utf-8"))
    shapes = doc["shapes"]

    assert shapes["CreateDocumentationPartRequest"]["required"] == ["restApiId", "location", "properties"]
    assert shapes["DocumentationPartLocation"]["required"] == ["type"]
    assert shapes["DocumentationPartLocationStatusCode"]["pattern"] == r"^([1-5]\d\d|\*|\s*)$"
    assert "API" in shapes["DocumentationPartType"]["enum"]
    assert shapes["GetDocumentationPartsRequest"]["members"]["locationStatus"] == "LocationStatusType"
    assert shapes["LocationStatusType"]["enum"] == ["DOCUMENTED", "UNDOCUMENTED"]


def test_ecr_shapes_name_the_ecr_model_and_its_registry_setting_actions():
    assert regen_ecr_shapes.MODEL == Path("ecr/2015-09-21")
    assert regen_ecr_shapes.OUTPUT.name == "ecr-request-shapes.json"
    assert "PutReplicationConfiguration" in regen_ecr_shapes.OPERATIONS


def test_payment_cryptography_shapes_name_its_model_and_its_control_plane_actions():
    assert regen_payment_cryptography_shapes.MODEL == Path("payment-cryptography/2021-09-14")
    assert regen_payment_cryptography_shapes.OUTPUT.name == "payment-cryptography-request-shapes.json"
    # The generator names no operation, so the file carries the model's whole set.
    assert not hasattr(regen_payment_cryptography_shapes, "OPERATIONS")
    operations = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))["operations"]
    assert "TagResource" in operations
    assert "UpdateAlias" in operations


def test_payment_cryptography_shapes_widen_the_partition_the_model_writes():
    doc = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))

    # The model writes the commercial partition; the service writes every partition it serves.
    assert r.COMMERCIAL not in doc["shapes"]["KeyArn"]["pattern"]
    assert r.EVERY_PARTITION in doc["shapes"]["KeyArn"]["pattern"]
    assert r.EVERY_PARTITION in doc["shapes"]["ResourceArn"]["pattern"]


def test_payment_cryptography_shapes_widen_the_region_on_every_pattern_the_model_writes_it():
    doc = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))

    patterns = [shape["pattern"] for shape in doc["shapes"].values() if "pattern" in shape]
    assert not [p for p in patterns if r.COMMERCIAL_REGION in p]
    assert doc["shapes"]["Region"]["pattern"] == r.EVERY_REGION
    for name in ("KeyArn", "KeyArnOrKeyAliasType", "ResourceArn"):
        assert r.EVERY_REGION in doc["shapes"][name]["pattern"]


def test_payment_cryptography_shapes_take_every_published_region_of_its_partition():
    shapes = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))["shapes"]
    partitions = json.loads(
        (regen_payment_cryptography_shapes.OUTPUT.parent / "partitions.json").read_text(encoding="utf-8"))["partitions"]
    regions = [(partition["id"], region["id"]) for partition in partitions for region in partition["regions"]]

    assert len(regions) >= 46
    for partition, region in regions:
        assert re.fullmatch(shapes["Region"]["pattern"], region), region
        key = f"arn:{partition}:payment-cryptography:{region}:000000000000:key/0123456789abcdef"
        alias = f"arn:{partition}:payment-cryptography:{region}:000000000000:alias/team/card-key"
        assert re.fullmatch(shapes["KeyArn"]["pattern"], key), key
        assert re.fullmatch(shapes["ResourceArn"]["pattern"], key), key
        assert re.fullmatch(shapes["KeyArnOrKeyAliasType"]["pattern"], key), key
        assert re.fullmatch(shapes["KeyArnOrKeyAliasType"]["pattern"], alias), alias


@pytest.mark.parametrize("region", [
    "us-gov", "us-gov-west-", "-gov-west-1", "u-gov-west-1", "europe-de-east-1", "US-GOV-WEST-1", "us_gov_west_1",
])
def test_payment_cryptography_shapes_refuse_what_is_not_a_region(region):
    shapes = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))["shapes"]

    assert not re.fullmatch(shapes["Region"]["pattern"], region)
    key = f"arn:aws:payment-cryptography:{region}:000000000000:key/0123456789abcdef"
    for name in ("KeyArn", "KeyArnOrKeyAliasType", "ResourceArn"):
        assert not re.fullmatch(shapes[name]["pattern"], key), name


def test_payment_cryptography_shapes_take_the_key_an_arn_only_member_names():
    doc = json.loads(regen_payment_cryptography_shapes.OUTPUT.read_text(encoding="utf-8"))

    # KeyArn and ResourceArn take the ARN of a key alone; KeyIdentifier also takes an alias.
    assert doc["shapes"]["KeyArn"]["pattern"].endswith("key/[0-9a-zA-Z]{16,64}")
    assert "alias/" not in doc["shapes"]["KeyArn"]["pattern"]
    assert "alias/" in doc["shapes"]["KeyArnOrKeyAliasType"]["pattern"]
