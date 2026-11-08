"""Request validation for imported, recursive sibling models."""

import http.client
import json
import time
import urllib.parse
import uuid
from contextlib import closing

import boto3
import pytest
from botocore.config import Config


@pytest.mark.parametrize("version", ["openapi", "swagger"])
@pytest.mark.parametrize("model_reference", ["local", "url"])
def test_imported_model_references(aws_config, version, model_reference):
    prefix = "#/components/schemas/" if version == "openapi" else "#/definitions/"
    schemas = {
        "Status": {"type": "string", "enum": ["READY"]},
        "Request": {"type": "object", "required": ["status"], "properties": {
            "status": {"$ref": prefix + "Status"}, "child": {"$ref": prefix + "Request"}}},
    }
    operation = {
        "responses": {"200": {"description": "accepted"}},
        "x-amazon-apigateway-request-validator": "body",
        "x-amazon-apigateway-integration": {
            "type": "mock", "requestTemplates": {"application/json": '{"statusCode":200}'},
            "responses": {"default": {"statusCode": "200",
                "responseTemplates": {"application/json": '{"accepted":true}'}}}},
    }
    document = {
        "info": {"title": "model-references-" + uuid.uuid4().hex, "version": "1"},
        "paths": {"/status": {"post": operation}},
        "x-amazon-apigateway-request-validators": {
            "body": {"validateRequestBody": True, "validateRequestParameters": False}},
    }
    if version == "openapi":
        document["openapi"] = "3.0.1"
        document["components"] = {"schemas": schemas}
        operation["requestBody"] = {
            "content": {"application/json": {"schema": {"$ref": prefix + "Request"}}}}
    else:
        document["swagger"] = "2.0"
        document["consumes"] = ["application/json"]
        document["produces"] = ["application/json"]
        document["definitions"] = schemas
        operation["parameters"] = [{"name": "body", "in": "body", "required": True,
                                    "schema": {"$ref": prefix + "Request"}}]

    with closing(boto3.client("apigateway", config=Config(retries={"max_attempts": 10}),
                              **aws_config)) as api:
        api_id = api.import_rest_api(body=json.dumps(document), failOnWarnings=True,
                                    parameters={"endpointConfigurationTypes": "REGIONAL"})["id"]
        try:
            if model_reference == "url":
                reference = f"https://apigateway.amazonaws.com/restapis/{api_id}/models/"
                request_schema = json.dumps(schemas["Request"]).replace(prefix, reference)
                api.update_model(restApiId=api_id, modelName="Request", patchOperations=[
                    {"op": "replace", "path": "/schema", "value": request_schema}])
            hostname = f"{api_id}.execute-api.{api.meta.region_name}.amazonaws.com"
            target = urllib.parse.urlsplit(aws_config.get("endpoint_url") or "https://" + hostname)
            connection_type = (http.client.HTTPSConnection if target.scheme == "https"
                               else http.client.HTTPConnection)

            def invoke(body):
                with closing(connection_type(target.hostname, target.port, timeout=30)) as connection:
                    connection.request("POST", "/probe/status", body=json.dumps(body),
                                       headers={"Host": hostname, "Content-Type": "application/json"})
                    response = connection.getresponse()
                    return response.status, json.loads(response.read())

            def await_response(body, expected):
                deadline = time.monotonic() + 60
                while True:
                    actual = invoke(body)
                    if actual == expected or time.monotonic() >= deadline:
                        assert actual == expected
                        return
                    time.sleep(1)

            api.create_deployment(restApiId=api_id, stageName="probe")
            valid = {"status": "READY"}
            await_response(valid, (200, {"accepted": True}))
            assert invoke({"status": "READY", "child": valid}) == (200, {"accepted": True})
            for invalid in [{}, {"status": "INVALID"}, {"status": "READY", "child": {"status": "INVALID"}}]:
                assert invoke(invalid) == (400, {"message": "Invalid request body"})

            api.update_model(restApiId=api_id, modelName="Status", patchOperations=[
                {"op": "replace", "path": "/schema", "value": '{"type":"string","enum":["DONE"]}'}])
            assert invoke(valid) == (200, {"accepted": True})
            assert invoke({"status": "DONE"}) == (400, {"message": "Invalid request body"})
            # AWS applies model changes to the data plane after redeployment and propagation.
            api.create_deployment(restApiId=api_id, stageName="probe")
            await_response({"status": "DONE"}, (200, {"accepted": True}))
            await_response(valid, (400, {"message": "Invalid request body"}))
        finally:
            api.delete_rest_api(restApiId=api_id)
