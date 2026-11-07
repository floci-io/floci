"""Tests for regen_rest_routes.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import gzip
import json

import pytest

import regen_rest_routes as r


def model(protocol: str, api_version: str, **operations: tuple[str, str]) -> dict:
    return {
        "metadata": {"protocol": protocol, "apiVersion": api_version},
        "operations": {name: {"http": {"method": method, "requestUri": uri}} for name, (method, uri) in operations.items()},
    }


def test_each_operation_keeps_its_method_and_request_uri_under_its_scope():
    doc = r.build({
        "s3": model("rest-xml", "2006-03-01", GetObject=("GET", "/{Bucket}/{Key+}?x-id=GetObject"),
                    DeleteBucketCors=("DELETE", "/{Bucket}?cors")),
        "apigatewayv2": model("rest-json", "2018-11-29", GetApis=("GET", "/v2/apis")),
    }, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_rest_routes.py", "botocore": "botocore test"}
    assert list(doc["models"]) == ["apigatewayv2", "s3"]
    assert doc["models"]["s3"] == {
        "credentialScope": "s3",
        "apiVersion": "2006-03-01",
        "operations": {
            "DeleteBucketCors": {"method": "DELETE", "requestUri": "/{Bucket}?cors"},
            "GetObject": {"method": "GET", "requestUri": "/{Bucket}/{Key+}?x-id=GetObject"},
        },
    }
    # A model is authorized under the scope it signs with, which need not be its own name.
    assert doc["models"]["apigatewayv2"]["credentialScope"] == "apigateway"


def test_each_operation_is_rendered_on_a_line_of_its_own():
    doc = r.build({"s3": model("rest-xml", "2006-03-01", GetObject=("GET", "/{Bucket}/{Key+}"),
                               HeadBucket=("HEAD", "/{Bucket}"))}, "botocore test")
    text = r.render(doc)

    assert json.loads(text) == doc
    assert '        "GetObject": {"method": "GET", "requestUri": "/{Bucket}/{Key+}"},' in text.splitlines()


def test_a_model_that_is_not_rest_is_refused():
    with pytest.raises(SystemExit):
        r.build({"s3": model("json", "2006-03-01", GetObject=("POST", "/"))}, "botocore test")


def test_check_passes_on_a_fresh_generation_and_fails_on_a_stale_one(tmp_path):
    for name in r.MODELS:
        directory = tmp_path / "data" / name / "2020-01-01"
        directory.mkdir(parents=True)
        with gzip.open(directory / "service-2.json.gz", "wt", encoding="utf-8") as f:
            json.dump(model("rest-json", "2020-01-01", Ping=("GET", "/ping")), f)
    output = tmp_path / "rest-routes.json"
    data = ["--botocore-data", str(tmp_path / "data"), "--output", str(output)]

    assert r.main(data) == 0
    assert r.main(data + ["--check"]) == 0
    output.write_text(output.read_text(encoding="utf-8").replace("/ping", "/pong"), encoding="utf-8")
    assert r.main(data + ["--check"]) == 1


def test_a_route_naming_two_operations_of_a_scope_the_rules_do_not_route_alone_is_refused():
    with pytest.raises(SystemExit):
        r.build({"lambda": model("rest-json", "2015-03-31", GetByName=("GET", "/things/{Name}"),
                                 GetById=("GET", "/things/{Id}/"))}, "botocore test")
    # One operation in two models of a scope, two routes a query tells apart, and two operations on one route of a
    # scope the rules route alone, all pass.
    doc = r.build({
        "lambda": model("rest-json", "2015-03-31", ListTags=("GET", "/tags/{ARN}"), GetThing=("GET", "/things/{Name}"),
                        ListThingTags=("GET", "/things/{Name}?tagging")),
        "lambda-microvms": model("rest-json", "2025-01-01", ListTags=("GET", "/tags/{Resource}")),
        "s3": model("rest-xml", "2006-03-01", PutObject=("PUT", "/{Bucket}/{Key+}"),
                    UploadPart=("PUT", "/{Bucket}/{Key+}")),
    }, "botocore test")
    assert doc["handRouted"] == dict(sorted(r.HAND_ROUTED.items()))
    assert set(doc["handRouted"]) == {"apigateway", "s3"}


def test_the_hand_routed_scopes_are_rendered_with_their_reasons():
    text = r.render(r.build({"s3": model("rest-xml", "2006-03-01", HeadBucket=("HEAD", "/{Bucket}"))},
                            "botocore test"))

    assert json.loads(text)["handRouted"] == dict(sorted(r.HAND_ROUTED.items()))
    assert text.splitlines()[2] == '  "handRouted": {'


@pytest.mark.parametrize("status", [400, 401, 402, 403])
def test_denial_status_comes_from_the_declared_error_shape(status):
    service = model("rest-json", "2020-01-01", GetThing=("GET", "/things/{Name}"))
    service["operations"]["GetThing"]["errors"] = [{"shape": "Denied"}]
    service["shapes"] = {
        "Denied": {"type": "structure", "exception": True,
                   "error": {"code": "AccessDeniedException", "httpStatusCode": status}},
        "Unused": {"type": "structure", "exception": True,
                   "error": {"code": "AccessDeniedException", "httpStatusCode": 499}},
    }
    doc = r.build({"quicksight": service}, "botocore test")
    assert doc["models"]["quicksight"]["accessDeniedStatus"] == status
    assert json.loads(r.render(doc))["models"]["quicksight"]["accessDeniedStatus"] == status


def test_a_non_denial_error_does_not_supply_the_denial_status():
    service = model("rest-json", "2020-01-01", GetThing=("GET", "/things/{Name}"))
    service["operations"]["GetThing"]["errors"] = [{"shape": "ValidationException"}]
    service["shapes"] = {"ValidationException": {"error": {"httpStatusCode": 400}}}
    doc = r.build({"quicksight": service}, "botocore test")
    assert "accessDeniedStatus" not in doc["models"]["quicksight"]


def test_models_sharing_a_scope_must_agree_on_the_denial_status():
    first = model("rest-json", "2020-01-01", GetThing=("GET", "/things/{Name}"))
    second = model("rest-json", "2020-01-01", GetThing=("GET", "/things/{Name}"))
    for service, status in ((first, 401), (second, 403)):
        service["operations"]["GetThing"]["errors"] = [{"shape": "AccessDeniedException"}]
        service["shapes"] = {"AccessDeniedException": {"error": {"httpStatusCode": status}}}
    with pytest.raises(SystemExit, match="conflicting access-denied HTTP statuses"):
        r.build({"lambda": first, "lambda-microvms": second}, "botocore test")
