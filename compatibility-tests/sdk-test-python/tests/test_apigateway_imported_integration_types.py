"""OpenAPI integration type readback verified against live API Gateway."""

import json
import uuid
from contextlib import closing

import boto3
import pytest
from botocore.exceptions import ClientError


def test_imported_integration_types(aws_config):
    expected_types = {
        "aws": "AWS", "aws_proxy": "AWS_PROXY", "http": "HTTP",
        "http_proxy": "HTTP_PROXY", "mock": "MOCK", "any": "MOCK",
        "AWS": "AWS", "AWS_PROXY": "AWS_PROXY", "HTTP": "HTTP",
        "HTTP_PROXY": "HTTP_PROXY", "MOCK": "MOCK",
        "Aws": "AWS", "Aws_Proxy": "AWS_PROXY", "Http": "HTTP",
        "Http_Proxy": "HTTP_PROXY", "Mock": "MOCK",
    }
    paths = {}
    for name in expected_types:
        integration_type = "mock" if name == "any" else name
        integration = {"type": integration_type, "timeoutInMillis": 12000}
        normalized_type = integration_type.lower()
        if normalized_type != "mock":
            integration["httpMethod"] = "POST"
        if normalized_type.startswith("aws"):
            integration["uri"] = (
                "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/"
                "functions/arn:aws:lambda:us-east-1:000000000000:function:fixture:live/invocations")
        elif normalized_type.startswith("http"):
            integration["uri"] = "https://example.com/original"
        if normalized_type in ("aws", "http", "mock"):
            integration["responses"] = {"default": {"statusCode": "200"}}
        operation = "x-amazon-apigateway-any-method" if name == "any" else "get"
        paths["/" + name] = {operation: {
            "responses": {"200": {"description": "ok"}},
            "x-amazon-apigateway-integration": integration,
        }}
    document = {"openapi": "3.0.2", "info": {
        "title": "integration-types-" + uuid.uuid4().hex, "version": "1"}, "paths": paths}

    with closing(boto3.client("apigateway", **aws_config)) as api:
        api_id = api.import_rest_api(body=json.dumps(document), failOnWarnings=True)["id"]
        try:
            for mode, timeout in [(None, 12000), ("merge", 14000), ("overwrite", 15000)]:
                for path in paths.values():
                    integration = next(iter(path.values()))["x-amazon-apigateway-integration"]
                    integration["timeoutInMillis"] = timeout
                    if mode and "uri" in integration:
                        integration["uri"] = integration["uri"].replace(
                            ":live/", ":next/").replace("/original", "/replacement")
                if mode:
                    api.put_rest_api(restApiId=api_id, mode=mode,
                                     body=json.dumps(document), failOnWarnings=True)
                resources = api.get_resources(restApiId=api_id)["items"]
                resources = {resource["path"]: resource["id"] for resource in resources}
                for name, expected_type in expected_types.items():
                    method = {"restApiId": api_id, "resourceId": resources["/" + name],
                              "httpMethod": "ANY" if name == "any" else "GET"}
                    integration = api.get_integration(**method)
                    embedded = api.get_method(**method)["methodIntegration"]
                    expected = next(iter(paths["/" + name].values()))["x-amazon-apigateway-integration"]
                    assert integration["type"] == embedded["type"] == expected_type
                    for field in ("uri", "httpMethod", "timeoutInMillis"):
                        if field in expected:
                            assert integration[field] == embedded[field] == expected[field]
        finally:
            api.delete_rest_api(restApiId=api_id)


def test_unknown_integration_types(aws_config):
    invalid_types = [
        "custom_type", "CuStOm_TyPe", "", " mock", "mock ", "http-proxy", "hıttp", "mοck", "moc\u212A",
    ]
    paths = {
        "/case" + str(index): {"get": {
            "responses": {"200": {"description": "ok"}},
            "x-amazon-apigateway-integration": {"type": value},
        }}
        for index, value in enumerate(invalid_types)
    }
    expected_warnings = {
        f"Unknown integration type '{value}' for 'GET /case{index}'. Ignoring."
        for index, value in enumerate(invalid_types)
    }
    document = {"openapi": "3.0.2", "info": {
        "title": "invalid-integration-types-" + uuid.uuid4().hex, "version": "1"}, "paths": paths}
    body = json.dumps(document)
    with closing(boto3.client("apigateway", **aws_config)) as api:
        with pytest.raises(ClientError) as rejected:
            api.import_rest_api(body=body, failOnWarnings=True)
        assert rejected.value.response["Error"]["Code"] == "BadRequestException"
        assert all(warning in rejected.value.response["Error"]["Message"] for warning in expected_warnings)
        imported = api.import_rest_api(body=body, failOnWarnings=False)
        api_id = imported["id"]
        try:
            for mode in [None, "merge", "overwrite"]:
                if mode:
                    with pytest.raises(ClientError) as rejected:
                        api.put_rest_api(restApiId=api_id, mode=mode, body=body, failOnWarnings=True)
                    assert rejected.value.response["Error"]["Code"] == "BadRequestException"
                    imported = api.put_rest_api(
                        restApiId=api_id, mode=mode, body=body, failOnWarnings=False)
                assert set(imported["warnings"]) == expected_warnings
                resources = api.get_resources(restApiId=api_id)["items"]
                for resource in resources:
                    if resource["path"] == "/":
                        continue
                    method = {"restApiId": api_id, "resourceId": resource["id"], "httpMethod": "GET"}
                    with pytest.raises(ClientError) as missing:
                        api.get_integration(**method)
                    assert missing.value.response["Error"]["Code"] == "NotFoundException"
                    assert "methodIntegration" not in api.get_method(**method)
        finally:
            api.delete_rest_api(restApiId=api_id)
