"""OpenAPI warnings and failOnWarnings through the API Gateway v1 SDK."""

import json
import uuid

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.fixture
def client(aws_config):
    api = boto3.client("apigateway", **aws_config)
    try:
        yield api
    finally:
        api.close()


def definition(title, warning=True, any_method=False):
    operation = {"responses": {"200": {"description": "ok"}}}
    if warning:
        operation["security"] = "not-an-array"
    return json.dumps({
        "openapi": "3.0.1", "info": {"title": title, "version": "1.0"},
        "paths": {"/p": {"x-amazon-apigateway-any-method" if any_method else "get": operation}},
    }).encode()


@pytest.mark.parametrize("any_method", [False, True])
def test_lenient_import_returns_warnings(client, any_method):
    imported = client.import_rest_api(
        body=definition(f"warn-{uuid.uuid4().hex}", any_method=any_method))
    try:
        assert any("security" in warning for warning in imported["warnings"])
        readback = client.get_rest_api(restApiId=imported["id"])
        assert readback["warnings"] == imported["warnings"]
    finally:
        client.delete_rest_api(restApiId=imported["id"])


@pytest.mark.parametrize("any_method", [False, True])
def test_strict_import_creates_nothing(client, any_method):
    title = f"reject-{uuid.uuid4().hex}"
    with pytest.raises(ClientError) as error:
        client.import_rest_api(body=definition(title, any_method=any_method), failOnWarnings=True)
    assert error.value.response["Error"]["Code"] == "BadRequestException"
    assert "security" in error.value.response["Error"]["Message"]
    assert title not in [api["name"] for api in client.get_rest_apis(limit=500)["items"]]


def test_strict_overwrite_preserves_resources_and_metadata(client):
    title = f"kept-{uuid.uuid4().hex}"
    imported = client.import_rest_api(body=definition(title, warning=False), failOnWarnings=True)
    api_id = imported["id"]
    try:
        before = client.get_resources(restApiId=api_id)["items"]
        with pytest.raises(ClientError) as error:
            client.put_rest_api(restApiId=api_id, mode="overwrite", failOnWarnings=True,
                                body=definition("rejected"))
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert client.get_rest_api(restApiId=api_id)["name"] == title
        assert client.get_resources(restApiId=api_id)["items"] == before
    finally:
        client.delete_rest_api(restApiId=api_id)


def test_lenient_overwrite_returns_warnings_then_clean_import_clears_them(client):
    imported = client.import_rest_api(body=definition(f"clean-{uuid.uuid4().hex}", warning=False))
    api_id = imported["id"]
    try:
        updated = client.put_rest_api(restApiId=api_id, mode="overwrite", failOnWarnings=False,
                                     body=definition("warning"))
        assert any("security" in warning for warning in updated["warnings"])
        client.put_rest_api(restApiId=api_id, mode="overwrite", failOnWarnings=True,
                            body=definition("clean", warning=False))
        assert not client.get_rest_api(restApiId=api_id).get("warnings")
    finally:
        client.delete_rest_api(restApiId=api_id)
