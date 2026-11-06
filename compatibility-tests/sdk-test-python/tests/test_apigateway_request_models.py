"""Request model selection verified against live API Gateway on 2026-10-12."""

import http.client
import json
import uuid
from contextlib import closing
from urllib.parse import urlsplit

import boto3
import pytest


@pytest.mark.parametrize("models", [
    {"application/json": "Named"},
    {"$default": "Fallback"},
    {"application/json": "Named", "$default": "Fallback"},
])
def test_request_model_content_type_selection(aws_config, endpoint_url, models):
    with closing(boto3.client("apigateway", **aws_config)) as api:
        api_id = api.create_rest_api(name="models-" + uuid.uuid4().hex)["id"]
        try:
            root = next(resource["id"] for resource in
                        api.get_resources(restApiId=api_id)["items"]
                        if resource["path"] == "/")
            validator = api.create_request_validator(
                restApiId=api_id, name="body", validateRequestBody=True,
                validateRequestParameters=False)["id"]
            for name, field in [("Named", "name"), ("Fallback", "fallback")]:
                api.create_model(
                    restApiId=api_id, name=name, contentType="application/json",
                    schema=json.dumps({"type": "object", "required": [field],
                                       "properties": {field: {"type": "string"}}}))
            method = {"restApiId": api_id, "resourceId": root, "httpMethod": "POST"}
            api.put_method(**method, authorizationType="NONE",
                           requestValidatorId=validator, requestModels=models)
            api.put_method_response(**method, statusCode="200")
            api.put_integration(
                **method, type="MOCK", passthroughBehavior="WHEN_NO_MATCH",
                requestTemplates={ct: '{"statusCode":200}' for ct in
                                  ["application/json", "text/plain"]})
            api.put_integration_response(
                **method, statusCode="200",
                responseTemplates={"application/json": '{"ok":true}'})
            api.create_deployment(restApiId=api_id, stageName="test")

            endpoint = urlsplit(endpoint_url)
            connection_type = (http.client.HTTPSConnection if endpoint.scheme == "https"
                               else http.client.HTTPConnection)
            for content_type in ["application/json", "text/plain",
                                 "application/json; charset=utf-8", None]:
                normalized = (content_type or "application/json").split(";")[0]
                model = models.get(normalized, models.get("$default"))
                required = {"Named": "name", "Fallback": "fallback"}.get(model)
                for payload in [{}, {"name": "ok"}, {"fallback": "ok"}]:
                    headers = {"Content-Type": content_type} if content_type else {}
                    # http.client preserves an absent Content-Type on a POST.
                    with closing(connection_type(endpoint.netloc, timeout=10)) as conn:
                        conn.request("POST", f"/execute-api/{api_id}/test/",
                                     body=json.dumps(payload), headers=headers)
                        response = conn.getresponse()
                        body = response.read()
                        expected = 200 if required is None or required in payload else 400
                        assert response.status == expected, (models, content_type, payload, body)
        finally:
            api.delete_rest_api(restApiId=api_id)
