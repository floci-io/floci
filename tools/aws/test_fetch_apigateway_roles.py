#!/usr/bin/env python3
"""Tests for fetch_apigateway_roles.py, on pages in the API Gateway API references' and developer guide's markup."""
from __future__ import annotations

import pytest

import fetch_apigateway_roles as f

ROLE_SENTENCE = ("Specifies the credentials required for the integration, if any. To specify an IAM Role for API Gateway "
                 "to assume, use the role's Amazon Resource Name (ARN).")


def rest_entry(anchor: str, member: str, description: str) -> str:
    return (f'<dl><dt id="{anchor}"><span class="term"><b>{member}</b></span></dt><dd><p>{description}</p>'
            f'<p>Type: String</p></dd></dl>')


def http_row(anchor: str, member: str, description: str) -> str:
    return (f'<table><tr><td tabindex="-1" id="{anchor}" xreflabel="{member}"><code class="code">{member}</code></td>'
            f'<td tabindex="-1"><p>string</p></td><td tabindex="-1">False</td><td tabindex="-1"><p>{description}</p>'
            f'</td></tr></table>')


def extension_page(extension: str, placement: str, member: str, description: str) -> str:
    return (f'<div id="main-col-body"><h1>{extension} object</h1><p>Specifies things. {placement}</p>'
            f'<table><tr><td>type</td><td>string</td><td>The type.</td></tr>'
            f'<tr><td>{member}</td><td>string</td><td>{description}</td></tr></table></div>')


DEFINITION = "Currently, only OpenAPI definition JSON/YAML files are supported."
PAGES = {
    f.V1 + "API_Integration.html": rest_entry("apigw-Type-Integration-credentials", "credentials", ROLE_SENTENCE),
    f.V1 + "API_Authorizer.html": rest_entry("apigw-Type-Authorizer-authorizerCredentials", "authorizerCredentials",
                                             ROLE_SENTENCE.replace("Role", "role")),
    f.V2 + "apis-apiid-integrations-integrationid.html": http_row(
        "apis-apiid-integrations-integrationid-prop-integration-credentialsarn", "credentialsArn", ROLE_SENTENCE),
    f.V2 + "apis-apiid-authorizers-authorizerid.html": http_row(
        "apis-apiid-authorizers-authorizerid-prop-authorizer-authorizercredentialsarn", "authorizerCredentialsArn",
        ROLE_SENTENCE),
    f.PATCH_PAGE: rest_entry("apigw-Type-PatchOperation-path", "path", 'The op operation\'s target. For example, if '
                             'the target resource has an updateable property of {"name":"value"}, the path for this '
                             'property is /name.'),
    f.V1 + "API_ImportRestApi.html": rest_entry("apigw-ImportRestApi-request-body", "body",
                                                "The POST request body. " + DEFINITION),
    f.V1 + "API_PutRestApi.html": rest_entry("apigw-PutRestApi-request-body", "body", "The PUT request body. " + DEFINITION),
    f.V2 + "apis.html": http_row("apis-prop-importapiinput-body", "body", "The OpenAPI definition."),
    f.V2 + "apis-apiid.html": http_row("apis-apiid-prop-reimportapiinput-body", "body", "The OpenAPI definition."),
    f.GUIDE + "api-gateway-swagger-extensions-integration.html": extension_page(
        "x-amazon-apigateway-integration", "This extension is an extended property of the OpenAPI Operation object.",
        "credentials", "For AWS IAM role-based credentials, specify the ARN of an appropriate IAM role."),
    f.GUIDE + "api-gateway-swagger-extensions-authorizer.html": extension_page(
        "x-amazon-apigateway-authorizer", "This extension applies to the security definition in OpenAPI 2 and the "
        "security scheme in OpenAPI 3 .", "authorizerCredentials",
        "The credentials required for invoking the authorizer, if any, in the form of an ARN of an IAM execution role."),
    f.ANY_METHOD_PAGE: '<div id="main-col-body"><h1>x-amazon-apigateway-any-method object</h1><p>Specifies the OpenAPI '
                       'Operation Object for the API Gateway catch-all ANY method in an OpenAPI <a href="#">Path Item '
                       'Object</a> .</p></div>',
}


def test_each_role_member_document_and_extension_is_read_with_its_description():
    built = f.build(lambda url: PAGES[url])
    assert [(m["model"], m["type"], m["member"]) for m in built["roleMembers"]] == [
        ("apigateway", "Integration", "credentials"), ("apigateway", "Authorizer", "authorizerCredentials"),
        ("apigatewayv2", "Integration", "credentialsArn"), ("apigatewayv2", "Authorizer", "authorizerCredentialsArn")]
    assert built["roleMembers"][0]["description"] == ROLE_SENTENCE
    assert [(d["operation"], d["member"]) for d in built["documents"]] == [
        ("ImportRestApi", "body"), ("PutRestApi", "body"), ("ImportApi", "body"), ("ReimportApi", "body")]
    assert "schema" not in built["documents"][2]
    assert [(e["extension"], e["on"], e["member"]) for e in built["extensions"]] == [
        ("x-amazon-apigateway-integration", "operation", "credentials"),
        ("x-amazon-apigateway-authorizer", "securityScheme", "authorizerCredentials")]
    assert built["anyMethod"]["extension"] == "x-amazon-apigateway-any-method"
    assert built["patchPath"]["model"] == "apigateway"


def test_a_page_whose_layout_or_wording_changed_is_refused():
    for url, page, message in (
            (f.V1 + "API_Integration.html", "<dl></dl>", "has no entry"),
            (f.V2 + "apis.html", "<table></table>", "has no property"),
            (f.V1 + "API_Authorizer.html", rest_entry("apigw-Type-Authorizer-authorizerCredentials",
                                                     "authorizerCredentials", "Some credentials."), "takes a role"),
            (f.PATCH_PAGE, rest_entry("apigw-Type-PatchOperation-path", "path", "A target."), "names a property"),
            (f.GUIDE + "api-gateway-swagger-extensions-integration.html", "<div id=\"main-col-body\"></div>",
             "what x-amazon-apigateway-integration extends"),
            (f.ANY_METHOD_PAGE, "<div id=\"main-col-body\"></div>", "ANY method")):
        changed = dict(PAGES, **{url: page})
        with pytest.raises(ValueError, match=message):
            f.build(lambda u: changed[u])


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(lambda url: PAGES[url])
    assert not f.stale(vendored, PAGES)
    assert f.stale(vendored, dict(PAGES, **{f.V2 + "apis.html": http_row("apis-prop-importapiinput-body", "body",
                                                                        "The OpenAPI definition, YAML or JSON.")}))
