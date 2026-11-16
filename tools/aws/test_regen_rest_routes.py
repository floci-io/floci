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


def test_check_passes_on_a_fresh_generation_and_fails_on_a_stale_one(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "DOCUMENTED", {})
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
    assert set(doc["handRouted"]) == {"s3"}


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


@pytest.mark.parametrize("name", ["controltower", "fis", "resource-explorer-2", "signer"])
@pytest.mark.parametrize("operation,method,source", [
    ("TagResource", "POST", "body"),
    ("UntagResource", "DELETE", "?resourceArn"),
    ("ListTagsForResource", "GET", "?resourceArn"),
])
def test_shared_tag_alternative_keeps_actual_member_source(name, operation, method, source):
    doc = r.build({name: model("rest-json", "2020-01-01", **{operation: (method, "/tags/{resourceArn}")})}, "test")
    route = doc["models"][name]["operations"][operation]
    assert route["requestUri"] == "/tags/{resourceArn+}"
    assert route["alternatives"] == [{"requestUri": "/tags", "located": {"resourceArn": source}}]
    assert json.loads(r.render(doc)) == doc


def test_shared_tag_alternative_collision_is_rejected():
    with pytest.raises(SystemExit, match="one route names more than one operation"):
        r.build({"controltower": model("rest-json", "2020-01-01",
            TagResource=("POST", "/tags/{resourceArn}"), OtherOperation=("POST", "/tags"))}, "test")


@pytest.mark.parametrize("name", ["payment-cryptography-data", "signer", "xray", "guardduty",
                                  "resource-explorer-2"])
def test_a_model_a_route_filter_moves_under_a_prefix_keeps_its_own_paths(name):
    # IAM matches a moved request on its path without the prefix its filter records, so the routes are the model's.
    doc = r.build({name: model("rest-json", "2020-01-01", GetThing=("GET", "/things/{ThingId}"))}, "test")
    assert doc["models"][name]["operations"]["GetThing"]["requestUri"] == "/things/{ThingId}"


def test_a_service_tagging_with_put_keeps_the_bare_post_the_shared_controller_serves():
    # API Gateway v1 tags with PUT on /tags/{resource_arn}, and SharedTagsController's bare /tags tags with a POST
    # naming the ARN in its body whatever the service's method, so that alternative says it is a POST.
    doc = r.build({"apigateway": model("rest-json", "2015-07-09", TagResource=("PUT", "/tags/{resource_arn}"),
                                       UntagResource=("DELETE", "/tags/{resource_arn}"),
                                       GetTags=("GET", "/tags/{resource_arn}"))}, "test")
    operations = doc["models"]["apigateway"]["operations"]
    assert operations["TagResource"] == {"method": "PUT", "requestUri": "/tags/{resource_arn+}", "alternatives": [
        {"method": "POST", "requestUri": "/tags", "located": {"resourceArn": "body"}}]}
    assert operations["UntagResource"]["alternatives"] == [
        {"requestUri": "/tags", "located": {"resourceArn": "?resourceArn"}}]
    assert operations["GetTags"]["alternatives"] == [{"requestUri": "/tags", "located": {"resourceArn": "?resourceArn"}}]
    assert json.loads(r.render(doc)) == doc


def test_an_alternative_is_keyed_by_its_own_method():
    # The bare POST of a PUT tags route collides with another operation's POST /tags, never with a PUT /tags.
    with pytest.raises(SystemExit, match="one route names more than one operation"):
        r.build({"apigateway": model("rest-json", "2015-07-09", TagResource=("PUT", "/tags/{resource_arn}"),
                                     CreateTag=("POST", "/tags"))}, "test")
    r.build({"apigateway": model("rest-json", "2015-07-09", TagResource=("PUT", "/tags/{resource_arn}"),
                                 PutTags=("PUT", "/tags"))}, "test")


def test_an_arn_label_the_controller_lets_span_slashes_is_greedy():
    # API Gateway v2's controller serves /v2/tags/{resourceArn: .+}, so an ARN sent unencoded reaches it; its other
    # routes and labels keep the model's one segment, and it has no bare route.
    doc = r.build({"apigatewayv2": model("rest-json", "2018-11-29", TagResource=("POST", "/v2/tags/{resource-arn}"),
                                         GetApi=("GET", "/v2/apis/{apiId}"))}, "test")
    operations = doc["models"]["apigatewayv2"]["operations"]
    assert operations["TagResource"] == {"method": "POST", "requestUri": "/v2/tags/{resource-arn+}"}
    assert operations["GetApi"] == {"method": "GET", "requestUri": "/v2/apis/{apiId}"}
    # OAM's tag routes are served the same way, its sink and link ARNs holding a slash.
    doc = r.build({"oam": model("rest-json", "2022-06-10", TagResource=("PUT", "/tags/{ResourceArn}"),
                                GetSink=("POST", "/GetSink"))}, "test")
    assert doc["models"]["oam"]["operations"]["TagResource"] == {"method": "PUT", "requestUri": "/tags/{ResourceArn+}"}


PAGE = "https://docs.aws.amazon.com/glue/latest/dg/iceberg-rest-apis.html"
SPEC = "https://github.com/apache/iceberg/blob/apache-iceberg-1.6.1/open-api/rest-catalog-open-api.yaml"
RAW = "https://raw.githubusercontent.com/apache/iceberg/apache-iceberg-1.6.1/open-api/rest-catalog-open-api.yaml"
DEFINITION_LINES = [
    "paths:",                                          # 1
    "  /v1/config:",                                   # 2
    "    get:",                                        # 3
    "      operationId: getConfig",                    # 4
    "  /v1/{prefix}/namespaces:",                      # 5
    "    parameters:",                                 # 6
    "      - $ref: '#/components/parameters/prefix'",  # 7
    "    get:",                                        # 8
    "      operationId: listNamespaces",               # 9
    "  /v1/{prefix}/namespaces/{namespace}:",          # 10
    "    get:",                                        # 11
    "      operationId: loadNamespaceMetadata",        # 12
    "    delete:",                                     # 13
    "      operationId: dropNamespace",                # 14
    "  /v1/{prefix}/namespaces/{namespace}/tables:",   # 15
    "    post:",                                       # 16
    "      operationId: createTable",                  # 17
    "  /v1/{prefix}/namespaces/{namespace}/tables/{table}:",  # 18
    "    get:",                                        # 19
    "      operationId: loadTable",                    # 20
    "    head:",                                       # 21
    "      operationId: tableExists",                  # 22
]


def operation_table(name: str, kind: str, path: str, action: str, event: str, line: int,
                    spec: str = SPEC) -> str:
    rows = [("Operation name", name), ("Type", kind), ("REST path", path), ("IAM action", action),
            ("Lake Formation permissions", "ALL"), ("CloudTrail event", event),
            ("Open API definition", f'<a href="{spec}#L{line}">{spec}#L{line}</a>')]
    return "<table><tbody>" + "".join(f"<tr><td>{key}</td><td>{value}</td></tr>" for key, value in rows) \
        + "</tbody></table>"


def page(*tables: str) -> str:
    return "<html><body><table><tr><th>Access pattern</th><th>Prefix</th><th>Example</th></tr></table>" \
        + "".join(tables) + "</body></html>"


ICEBERG = "Iceberg REST Catalog API"
SERVED = page(
    operation_table("GetConfig", ICEBERG, "GET /iceberg/v1/config", "glue:GetCatalog", "glue:GetCatalog", 3),
    operation_table("ListNamespaces", ICEBERG, "GET/iceberg/v1/catalogs/{catalog}/namespaces", "glue:GetDatabase",
                    "glue:GetDatabase", 8),
    operation_table("LoadNamespaceMetadata", ICEBERG, "GET/iceberg/v1/catalogs/{catalog}/namespaces/{ns}",
                    "glue:GetDatabase", "glue:GetDatabase", 11),
    operation_table("DeleteNamespace", ICEBERG, "DELETE/iceberg/v1/catalogs/{catalog}/namespces/{ns}",
                    "glue:DeleteDatabase", "glue:DeleteDatabase", 13),
    operation_table("CreateTable", ICEBERG, "GET /iceberg/v1/catalogs/{catalog}/namespaces/{ns}/tables",
                    "glue:CreateTable", "glue:CreateTable", 16),
    operation_table("CreateTable", "AWS Glue extensions API",
                    "POST/extensions/v1/catalogs/{catalog}/namespaces/{ns}/tables", "glue:CreateTable",
                    "glue:CreateTable", 107,
                    "https://github.com/awslabs/glue-extensions-for-iceberg/blob/main/glue-extensions-api.yaml"),
    operation_table("LoadTable", ICEBERG, "GET /iceberg/v1/catalogs/{catalog}/namespaces/{ns}/tables/{table}",
                    "glue:GetTable", "glue:GetTable", 19),
    operation_table("TableExists", ICEBERG, "HEAD/iceberg/v1/catalogs/{catalog}/namespaces/{ns}/tables/{table}",
                    "glue:GetTable", "glue:GetTable", 21),
)
GLUE = {"credentialScope": "glue", "page": PAGE, "type": ICEBERG}


def fetcher(pages: dict[str, str]):
    def fetch(url: str) -> str:
        return pages[url]
    return fetch


def test_a_documented_endpoint_takes_each_rows_route_from_the_definition_it_links_under_the_pages_prefix():
    model = r.build_documented(GLUE, fetcher({PAGE: SERVED, RAW: "\n".join(DEFINITION_LINES)}))

    assert model["credentialScope"] == "glue"
    assert model["apiVersion"] == "apache-iceberg-1.6.1"
    assert model["operations"] == {
        "CreateTable": {"method": "POST", "requestUri": "/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables"},
        "DeleteDatabase": {"method": "DELETE", "requestUri": "/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}"},
        "GetCatalog": {"method": "GET", "requestUri": "/iceberg/v1/config"},
        # Rows of one operation are its route, in the page's order, and its alternatives, with their own method.
        "GetDatabase": {"method": "GET", "requestUri": "/iceberg/v1/catalogs/{catalog}/namespaces",
                        "alternatives": [{"requestUri": "/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}",
                                          "located": {}}]},
        "GetTable": {"method": "GET", "requestUri": "/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}",
                     "alternatives": [{"method": "HEAD", "requestUri":
                                       "/iceberg/v1/catalogs/{catalog}/namespaces/{namespace}/tables/{table}",
                                       "located": {}}]},
    }
    # The page's misspelt segment and wrong method give way to the definition the row links, and are recorded.
    assert model["documented"]["page"] == PAGE
    assert model["documented"]["definitions"] == [RAW]
    assert model["documented"]["pageDiffers"] == [
        "DeleteNamespace: the page gives DELETE/iceberg/v1/catalogs/{catalog}/namespces/{ns}, its definition "
        "DELETE /v1/{prefix}/namespaces/{namespace}",
        "CreateTable: the page gives GET /iceberg/v1/catalogs/{catalog}/namespaces/{ns}/tables, its definition "
        "POST /v1/{prefix}/namespaces/{namespace}/tables",
    ]


def test_a_row_whose_iam_action_is_not_its_cloudtrail_event_is_refused():
    served = page(operation_table("ListNamespaces", ICEBERG, "GET/iceberg/v1/catalogs/{catalog}/namespaces",
                                  "glue:GetDatabases", "glue:GetDatabase", 8))
    with pytest.raises(SystemExit, match="is not its CloudTrail event"):
        r.build_documented(GLUE, fetcher({PAGE: served, RAW: "\n".join(DEFINITION_LINES)}))


def test_a_link_that_anchors_no_method_or_a_page_with_no_row_of_the_type_is_refused():
    served = page(operation_table("ListNamespaces", ICEBERG, "GET/iceberg/v1/catalogs/{catalog}/namespaces",
                                  "glue:GetDatabase", "glue:GetDatabase", 9))
    with pytest.raises(SystemExit, match="does not anchor an operation's method"):
        r.build_documented(GLUE, fetcher({PAGE: served, RAW: "\n".join(DEFINITION_LINES)}))
    with pytest.raises(SystemExit, match="documents no operation"):
        r.build_documented(GLUE, fetcher({PAGE: page(), RAW: ""}))


def test_check_carries_the_documented_routes_over_and_verify_compares_them_with_the_page(tmp_path, monkeypatch):
    monkeypatch.setattr(r, "DOCUMENTED", {"glue-iceberg-rest": GLUE})
    for name in r.MODELS:
        directory = tmp_path / "data" / name / "2020-01-01"
        directory.mkdir(parents=True)
        with gzip.open(directory / "service-2.json.gz", "wt", encoding="utf-8") as f:
            json.dump(model("rest-json", "2020-01-01", Ping=("GET", "/ping")), f)
    output = tmp_path / "rest-routes.json"
    data = ["--botocore-data", str(tmp_path / "data"), "--output", str(output)]
    documented = r.build_documented(GLUE, fetcher({PAGE: SERVED, RAW: "\n".join(DEFINITION_LINES)}))

    # A regeneration with no vendored routes of the endpoint cannot read them offline.
    with pytest.raises(SystemExit, match="--fetch-documented"):
        r.main(data)
    output.write_text(r.render(r.build({}, "test", {"glue-iceberg-rest": documented})), encoding="utf-8")
    assert r.main(data) == 0
    assert json.loads(output.read_text(encoding="utf-8"))["models"]["glue-iceberg-rest"] == documented
    assert r.main(data + ["--check"]) == 0

    vendored = r.carried_documented(output.read_text(encoding="utf-8"))
    assert r.verify_documented(vendored, fetcher({PAGE: SERVED, RAW: "\n".join(DEFINITION_LINES)})) == 0
    moved = SERVED.replace("glue:GetCatalog", "glue:GetCatalogs")
    assert r.verify_documented(vendored, fetcher({PAGE: moved, RAW: "\n".join(DEFINITION_LINES)})) == 1
    fewer = SERVED.replace(
        operation_table("TableExists", ICEBERG, "HEAD/iceberg/v1/catalogs/{catalog}/namespaces/{ns}/tables/{table}",
                        "glue:GetTable", "glue:GetTable", 21), "")
    assert r.verify_documented(vendored, fetcher({PAGE: fewer, RAW: "\n".join(DEFINITION_LINES)})) == 1


def test_vendored_documented_routes_read_from_another_page_or_malformed_are_refused(monkeypatch):
    monkeypatch.setattr(r, "DOCUMENTED", {"glue-iceberg-rest": GLUE})
    documented = r.build_documented(GLUE, fetcher({PAGE: SERVED, RAW: "\n".join(DEFINITION_LINES)}))
    elsewhere = json.loads(json.dumps(documented))
    elsewhere["documented"]["page"] = "https://example.com/other.html"
    with pytest.raises(SystemExit, match="are not read from"):
        r.carried_documented(r.render(r.build({}, "test", {"glue-iceberg-rest": elsewhere})))
    malformed = json.loads(json.dumps(documented))
    malformed["operations"]["GetCatalog"] = {"method": "FETCH", "requestUri": "/iceberg/v1/config"}
    with pytest.raises(SystemExit, match="malformed"):
        r.carried_documented(r.render(r.build({}, "test", {"glue-iceberg-rest": malformed})))
