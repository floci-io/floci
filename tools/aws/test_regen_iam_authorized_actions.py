"""Tests for regen_iam_authorized_actions.

Run with: pytest tools/aws -q  (or: make iam-authorized-actions-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_authorized_actions as r


def service(namespace: str, operations: dict[str, list[str]], actions: list[str] | None = None) -> dict:
    """A reference document shaped like AWS's. Each operation maps to the actions authorizing it,
    written `service:Name`; `actions` are the IAM actions the namespace publishes."""
    return {
        "Name": namespace,
        "Actions": [{"Name": name} for name in (actions or [])],
        "Operations": [
            {"Name": name,
             "AuthorizedActions": [{"Service": a.split(":")[0], "Name": a.split(":")[1]} for a in authorized]}
            for name, authorized in operations.items()
        ],
    }


ANCHOR_SERVICES = [
    service("sqs", {"SendMessageBatch": ["sqs:SendMessage"],
                    "DeleteMessageBatch": ["sqs:DeleteMessage"],
                    "ChangeMessageVisibilityBatch": ["sqs:ChangeMessageVisibility"]},
            ["SendMessage", "DeleteMessage", "ChangeMessageVisibility"]),
    service("sns", {"PublishBatch": ["sns:Publish"]}, ["Publish"]),
    service("dynamodb", {"TransactGetItems": ["dynamodb:GetItem"]}, ["GetItem"]),
    service("budgets", {"CreateBudget": ["aws-portal:ModifyBilling", "budgets:ModifyBudget", "budgets:TagResource"],
                        "DescribeBudgets": ["aws-portal:ViewBilling", "budgets:ViewBudget"]},
            ["ModifyBudget", "TagResource", "ViewBudget"]),
]


# --------------------------------------------------------------------------- #
# authorized_actions_from: which operations are vendored
# --------------------------------------------------------------------------- #
def test_an_operation_authorized_by_one_differently_named_action_is_kept():
    found = r.authorized_actions_from([service("sqs", {"SendMessageBatch": ["sqs:SendMessage"]},
                                               ["SendMessage"])])
    assert found == {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}


def test_an_operation_authorized_as_its_own_name_is_not_vendored():
    assert r.authorized_actions_from([service("sqs", {"SendMessage": ["sqs:SendMessage"]},
                                              ["SendMessage"])]) == {}


def test_several_actions_are_left_to_the_operations_own_name():
    # CreateAutoScalingGroup also lists the tag and PassRole permissions, which AWS checks only
    # when tags or a role are passed; requiring them all would deny calls AWS allows.
    found = r.authorized_actions_from([service("autoscaling", {"CreateAutoScalingGroup": [
        "autoscaling:CreateAutoScalingGroup", "autoscaling:CreateOrUpdateTags", "iam:PassRole"]})])
    assert found == {}


def test_an_operation_with_no_action_is_missing_data_not_a_free_call():
    assert r.authorized_actions_from([service("elasticbeanstalk", {"AbortEnvironmentUpdate": []})]) == {}


def test_an_action_in_another_namespace_is_not_vendored():
    found = r.authorized_actions_from([
        service("transcribe", {"UpdateLanguageModel": ["iam:PassRole"]}),
        service("opensearch", {"AddTags": ["es:AddTags"]}),
    ])
    assert found == {}


def test_an_operation_whose_own_name_is_also_an_action_is_left_alone():
    found = r.authorized_actions_from([service(
        "glue", {"CreatePartitionIndex": ["glue:UpdateTable"]}, ["CreatePartitionIndex", "UpdateTable"])])
    assert found == {}


def test_a_name_differing_only_in_case_is_the_same_action():
    found = r.authorized_actions_from([service(
        "memorydb", {"DeleteACL": ["memorydb:DeleteAcl"]}, ["DeleteAcl"])])
    assert found == {}


def test_a_tagging_action_alone_is_a_side_permission():
    found = r.authorized_actions_from([service(
        "ec2", {"CreateCapacityReservationDateChangeQuote": ["ec2:CreateTags"]}, ["CreateTags"])])
    assert found == {}


def test_a_billing_console_permission_does_not_count():
    # The aws-portal actions gate the Billing console; the Budgets API is authorized by budgets alone.
    found = r.authorized_actions_from([service(
        "budgets", {"CreateNotification": ["aws-portal:ModifyBilling", "budgets:ModifyBudget"],
                    "DescribeBudgets": ["aws-portal:ViewBilling", "budgets:ViewBudget"]},
        ["ModifyBudget", "ViewBudget"])])
    assert found == {"budgets": {"CreateNotification": "budgets:ModifyBudget",
                                 "DescribeBudgets": "budgets:ViewBudget"}}


def test_a_console_permission_alone_is_no_action():
    assert r.authorized_actions_from([service("budgets", {"DescribeBudget": ["aws-portal:ViewBilling"]})]) == {}


def test_a_tagging_action_beside_one_other_is_the_tag_on_create_permission():
    found = r.authorized_actions_from([service(
        "budgets", {"CreateBudget": ["aws-portal:ModifyBilling", "budgets:ModifyBudget", "budgets:TagResource"]},
        ["ModifyBudget", "TagResource"])])
    assert found == {"budgets": {"CreateBudget": "budgets:ModifyBudget"}}


def test_a_tagging_action_beside_several_leaves_them_several():
    found = r.authorized_actions_from([service(
        "budgets", {"CreateBudget": ["budgets:ModifyBudget", "budgets:ViewBudget", "budgets:TagResource"]})])
    assert found == {}


def test_an_operation_named_for_no_action_is_the_one_of_its_actions_its_name_starts_with():
    # CloudFront's CreateDistributionWithTags is no IAM action: AWS checks cloudfront:CreateDistribution, and the
    # others it lists only for what the request adds (a connection group, a VPC origin, its tags).
    found = r.authorized_actions_from([service("cloudfront", {"CreateDistributionWithTags": [
        "cloudfront:CreateConnectionGroup", "cloudfront:CreateDistribution", "cloudfront:GetVpcOrigin",
        "cloudfront:TagResource"]}, ["CreateConnectionGroup", "CreateDistribution", "GetVpcOrigin", "TagResource"])])
    assert found == {"cloudfront": {"CreateDistributionWithTags": "cloudfront:CreateDistribution"}}


def test_an_action_matching_a_name_only_in_case_is_not_its_prefix():
    # API Gateway names its actions for HTTP verbs: PutIntegration lists apigateway:PUT beside others, which is no
    # prefix of its name.
    found = r.authorized_actions_from([service("apigateway", {"PutIntegration": [
        "apigateway:PUT", "apigateway:POST"]}, ["PUT", "POST"])])
    assert found == {}


def test_a_plural_of_an_action_is_not_named_for_it():
    # S3's DeleteObjects deletes several objects, each checked as s3:DeleteObject where it is deleted.
    found = r.authorized_actions_from([service("s3", {"DeleteObjects": [
        "s3:DeleteObject", "s3:DeleteObjectVersion"]}, ["DeleteObject", "DeleteObjectVersion"])])
    assert found == {}


def test_namespaces_and_operations_are_sorted():
    found = r.authorized_actions_from([
        service("sqs", {"SendMessageBatch": ["sqs:SendMessage"],
                        "DeleteMessageBatch": ["sqs:DeleteMessage"]}),
        service("dms", {"DescribeDataProviders": ["dms:ListDataProviders"]}),
    ])
    assert list(found) == ["dms", "sqs"]
    assert list(found["sqs"]) == ["DeleteMessageBatch", "SendMessageBatch"]


@pytest.mark.parametrize("namespace", [None, "", "Not A Namespace", 7])
def test_an_unusable_namespace_is_rejected(namespace):
    with pytest.raises(ValueError):
        r.authorized_actions_from([{"Name": namespace, "Operations": []}])


def test_an_operation_without_a_name_is_rejected():
    with pytest.raises(ValueError):
        r.authorized_actions_from([{"Name": "sqs", "Operations": [{"AuthorizedActions": []}]}])


@pytest.mark.parametrize("authorized", [
    [{"Service": "sqs", "Name": 7}],
    [{"Service": "sqs"}],
    [{"Service": None, "Name": "SendMessage"}],
    [None],
    ["sqs:SendMessage"],
    "sqs:SendMessage",
])
def test_an_authorized_action_record_of_the_wrong_shape_is_a_malformed_response(authorized):
    # Skipping it would vendor a map that silently lacks the operation.
    with pytest.raises(ValueError):
        r.authorized_actions_from([{"Name": "sqs", "Operations": [
            {"Name": "SendMessageBatch", "AuthorizedActions": authorized}]}])


def test_an_action_name_iam_would_not_accept_is_left_out_not_rejected():
    # AWS publishes this one for vpc-lattice; a well-formed record naming it keeps the operation's own name.
    found = r.authorized_actions_from([service(
        "vpc-lattice", {"AssociateServiceNetwork": ["vpc-lattice:AssociateViaAWSService-EventsAndStates"]})])
    assert found == {}


def with_sdk_clients(document: dict, *clients: str) -> dict:
    for operation in document["Operations"]:
        operation["SDK"] = [{"Name": client, "Method": "m", "Package": "Boto3"} for client in clients]
    return document


def test_an_operation_listed_for_several_sdk_clients_keeps_its_own_name():
    # S3 Control's GetPublicAccessBlock is the account's, which the one action given for both misses.
    shared = with_sdk_clients(service("s3", {"GetPublicAccessBlock": ["s3:GetBucketPublicAccessBlock"]}),
                              "s3", "s3control")
    assert r.authorized_actions_from([shared]) == {}

    single = with_sdk_clients(service("sqs", {"SendMessageBatch": ["sqs:SendMessage"]}), "sqs")
    assert r.authorized_actions_from([single]) == {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}


def test_a_name_listed_twice_with_different_answers_keeps_its_own_name():
    found = r.authorized_actions_from([
        service("s3", {"GetPublicAccessBlock": ["s3:GetBucketPublicAccessBlock"]}),
        service("s3", {"GetPublicAccessBlock": ["s3:GetAccountPublicAccessBlock"]}),
    ])
    assert found == {}

    found = r.authorized_actions_from([
        service("s3", {"GetPublicAccessBlock": ["s3:GetBucketPublicAccessBlock"]}),
        service("s3", {"GetPublicAccessBlock": ["s3:GetBucketPublicAccessBlock", "iam:PassRole"]}),
    ])
    assert found == {}


def test_a_name_listed_twice_with_the_same_answer_is_kept():
    found = r.authorized_actions_from([
        service("sqs", {"SendMessageBatch": ["sqs:SendMessage"]}),
        service("sqs", {"SendMessageBatch": ["sqs:SendMessage"]}),
    ])
    assert found == {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}


def test_a_malformed_sdk_list_is_a_malformed_response():
    document = service("sqs", {"SendMessageBatch": ["sqs:SendMessage"]})
    document["Operations"][0]["SDK"] = [None]
    with pytest.raises(ValueError):
        r.authorized_actions_from([document])


# --------------------------------------------------------------------------- #
# validate: the offline --check gate
# --------------------------------------------------------------------------- #
def anchored(extra: dict[str, dict[str, str]] | None = None) -> dict:
    document = r.build(ANCHOR_SERVICES, "a test", [])
    for namespace, operations in (extra or {}).items():
        document["authorizedActions"].setdefault(namespace, {}).update(operations)
    document["authorizedActions"] = {
        namespace: dict(sorted(operations.items()))
        for namespace, operations in sorted(document["authorizedActions"].items())}
    return document


def test_a_freshly_built_document_validates():
    assert r.validate(anchored(), []) == []


def test_an_action_in_another_namespace_is_rejected():
    problems = r.validate(anchored({"sqs": {"PurgeQueueBatch": "sns:Publish"}}), [])
    assert any("not an action of sqs" in problem for problem in problems)


def test_an_operation_mapped_to_its_own_name_is_rejected():
    problems = r.validate(anchored({"sqs": {"Purgequeue": "sqs:PurgeQueue"}}), [])
    assert any("its own name" in problem for problem in problems)


def test_a_missing_anchor_is_rejected():
    document = anchored()
    del document["authorizedActions"]["sns"]
    assert any("sns:PublishBatch" in problem for problem in r.validate(document, []))


def test_an_anchor_mapped_elsewhere_is_rejected():
    document = anchored()
    document["authorizedActions"]["sqs"]["SendMessageBatch"] = "sqs:ReceiveMessage"
    assert any("sqs:SendMessageBatch" in problem for problem in r.validate(document, []))


def test_an_unsorted_namespace_map_is_rejected():
    document = anchored()
    document["authorizedActions"] = dict(reversed(list(document["authorizedActions"].items())))
    assert "authorizedActions is not sorted by namespace" in r.validate(document, [])


def test_an_empty_map_is_rejected():
    assert r.validate({"authorizedActions": {}}, []) == ["authorizedActions is missing or empty"]


# --------------------------------------------------------------------------- #
# verify_against: the online --verify gate
# --------------------------------------------------------------------------- #
def test_an_identical_map_verifies_clean():
    vendored = {"sqs": {"SendMessageBatch": "sqs:SendMessage"}}
    assert r.verify_against(vendored, vendored) == ([], [])


def test_a_claim_aws_does_not_make_fails():
    untrue, _ = r.verify_against({"sqs": {"SendMessageBatch": "sqs:SendMessageBatch"}},
                                 {"sqs": {"SendMessageBatch": "sqs:SendMessage"}})
    assert untrue == ["AWS does not authorize sqs:SendMessageBatch as sqs:SendMessageBatch "
                      "but as sqs:SendMessage"]


def test_an_entry_aws_no_longer_publishes_fails():
    untrue, _ = r.verify_against({"sqs": {"SendMessageBatch": "sqs:SendMessage"}}, {})
    assert untrue == ["AWS does not authorize sqs:SendMessageBatch as sqs:SendMessage"]


def test_an_entry_aws_has_added_is_reported_without_failing():
    untrue, added = r.verify_against({}, {"sns": {"PublishBatch": "sns:Publish"}})
    assert untrue == []
    assert added == ["sns:PublishBatch -> sns:Publish"]


# --------------------------------------------------------------------------- #
# main: the file round trip
# --------------------------------------------------------------------------- #
def test_generating_from_saved_files_writes_a_file_check_accepts(tmp_path):
    sources = tmp_path / "reference"
    sources.mkdir()
    for document in ANCHOR_SERVICES:
        (sources / f"{document['Name']}.json").write_text(json.dumps(document), encoding="utf-8")
    output = tmp_path / "iam-authorized-actions.json"
    routes = no_routes(tmp_path)
    assert r.main(["--source-dir", str(sources), "--output", str(output), "--routes", routes]) == 0
    assert r.main(["--check", "--output", str(output), "--routes", routes]) == 0
    assert r.main(["--verify", "--source-dir", str(sources), "--output", str(output)]) == 0


def no_routes(tmp_path) -> str:
    """A routes file reading no scope's routes."""
    routes = tmp_path / "rest-routes.json"
    routes.write_text(json.dumps({"handRouted": {}, "models": {}}), encoding="utf-8")
    return str(routes)


def saved_sources(tmp_path, documents: list[dict]):
    sources = tmp_path / "reference"
    sources.mkdir()
    for index, document in enumerate(documents):
        (sources / f"{index}-{document['Name']}.json").write_text(json.dumps(document), encoding="utf-8")
    return sources


MALFORMED = {"Name": "sqs", "Operations": [
    {"Name": "ChangeMessageVisibilityBatch", "AuthorizedActions": [{"Service": "sqs", "Name": 7}]}]}


def test_a_malformed_record_makes_generation_write_nothing(tmp_path):
    sources = saved_sources(tmp_path, ANCHOR_SERVICES + [MALFORMED])
    output = tmp_path / "iam-authorized-actions.json"
    assert r.main(["--source-dir", str(sources), "--output", str(output), "--routes", no_routes(tmp_path)]) == 2
    assert not output.exists()


def test_verify_reports_a_malformed_record_as_an_unreadable_reference(tmp_path):
    # Status 2 tells CI the reference could not be read, rather than that the vendored map is wrong.
    output = tmp_path / "iam-authorized-actions.json"
    assert r.main(["--source-dir", str(saved_sources(tmp_path, ANCHOR_SERVICES)), "--output", str(output),
                   "--routes", no_routes(tmp_path)]) == 0
    malformed = tmp_path / "malformed"
    malformed.mkdir()
    (malformed / "sqs.json").write_text(json.dumps({"Name": "sqs", "Operations": [
        {"Name": "SendMessageBatch", "AuthorizedActions": [None]}]}), encoding="utf-8")
    assert r.main(["--verify", "--source-dir", str(malformed), "--output", str(output)]) == 2


# --------------------------------------------------------------------------- #
# actionsWithoutResourceTypes: the actions a route no rule maps may be checked as
# --------------------------------------------------------------------------- #
def typed(namespace: str, actions: dict[str, list[str]]) -> dict:
    """A reference document whose actions act on the resource types given."""
    return {"Name": namespace, "Operations": [],
            "Actions": [{"Name": name, "Resources": [{"Name": kind} for kind in kinds]}
                        for name, kinds in actions.items()]}


ROUTES = {"handRouted": {"hand": "the rules route it alone"},
          "models": {"svc": {"credentialScope": "svc"}, "svc-v2": {"credentialScope": "svc"},
                     "other": {"credentialScope": "other"}, "hand": {"credentialScope": "hand"}}}


def test_the_routed_scopes_are_every_models_but_the_hand_routed_ones():
    assert r.routed_namespaces(ROUTES) == ["other", "svc"]


def test_each_routed_scope_lists_its_actions_of_no_resource_type():
    documents = [typed("svc", {"ListThings": [], "GetThing": ["thing"], "CreateThing": ["thing", "tag"]}),
                 typed("other", {"GetOther": ["other"]}), typed("unrouted", {"ListUnrouted": []})]
    assert r.actions_without_resource_types(documents, ["other", "svc"]) == {"other": [], "svc": ["ListThings"]}
    built = r.build(ANCHOR_SERVICES + documents, "a test", ["other", "svc"])
    assert built["actionsWithoutResourceTypes"] == {"other": [], "svc": ["ListThings"]}
    assert r.validate(built, ["other", "svc"]) == []


def test_a_routed_scope_the_reference_does_not_have_is_an_error():
    with pytest.raises(ValueError, match="no namespace svc"):
        r.actions_without_resource_types([typed("other", {})], ["other", "svc"])


def test_the_listed_scopes_must_be_the_routed_ones_each_sorted():
    document = r.build(ANCHOR_SERVICES + [typed("svc", {"ListThings": [], "ListMore": []})], "a test", ["svc"])
    assert r.validate(document, ["svc"]) == []
    assert any("not the scopes rest-routes.json reads" in problem for problem in r.validate(document, ["other", "svc"]))
    document["actionsWithoutResourceTypes"]["svc"].reverse()
    assert "actionsWithoutResourceTypes.svc is not sorted, or repeats an action" in r.validate(document, ["svc"])
    del document["actionsWithoutResourceTypes"]
    assert "actionsWithoutResourceTypes is missing" in r.validate(document, ["svc"])


def test_verify_fails_on_a_listed_action_aws_gives_a_resource_type_and_reports_one_it_added():
    untrue, added = r.verify_unscoped_against({"svc": ["GetThing", "ListThings"]},
                                              {"svc": ["ListThings", "ListMore"]})
    assert untrue == ["AWS gives svc:GetThing a resource type, or no longer has it"]
    assert added == ["svc:ListMore"]
    assert r.verify_unscoped_against({"svc": ["ListThings"]}, {"svc": ["ListThings"]}) == ([], [])


def test_the_round_trip_lists_the_routed_scopes_actions(tmp_path):
    documents = ANCHOR_SERVICES + [typed("svc", {"ListThings": [], "GetThing": ["thing"]}), typed("other", {})]
    sources = saved_sources(tmp_path, documents)
    routes = tmp_path / "rest-routes.json"
    routes.write_text(json.dumps(ROUTES), encoding="utf-8")
    output = tmp_path / "iam-authorized-actions.json"
    assert r.main(["--source-dir", str(sources), "--output", str(output), "--routes", str(routes)]) == 0
    assert json.loads(output.read_text())["actionsWithoutResourceTypes"] == {"other": [], "svc": ["ListThings"]}
    assert r.main(["--check", "--output", str(output), "--routes", str(routes)]) == 0
    assert r.main(["--verify", "--source-dir", str(sources), "--output", str(output)]) == 0
    (tmp_path / "later").mkdir()
    given = saved_sources(tmp_path / "later", ANCHOR_SERVICES + [typed("svc", {"ListThings": ["thing"]}),
                                                                 typed("other", {})])
    assert r.main(["--verify", "--source-dir", str(given), "--output", str(output)]) == 1


# --------------------------------------------------------------------------- #
# authorizedByMethod: the operations checked as the action their route's method names
# --------------------------------------------------------------------------- #
def by_method_service(operations: dict[str, list[str]], sdks: dict[str, list[str]] | None = None) -> dict:
    """An API Gateway-like reference document: its methods are actions, beside actions of their own names."""
    document = service("api", operations, ["GET", "POST", "PUT", "PATCH", "DELETE", "UpdatePolicy", "CreatePortal"])
    for operation in document["Operations"]:
        operation["SDK"] = [{"Name": name} for name in (sdks or {}).get(operation["Name"], ["api"])]
    return document


METHOD_ROUTES = {"handRouted": {"hand": "the rules route it alone"}, "models": {
    "api": {"credentialScope": "api", "operations": {
        "TagResource": {"method": "PUT", "requestUri": "/tags/{arn+}",
                        "alternatives": [{"method": "POST", "requestUri": "/tags", "located": {"arn": "body"}}]},
        "GetStage": {"method": "GET", "requestUri": "/apis/{id}/stages/{name}"},
        "CreateApi": {"method": "POST", "requestUri": "/apis"},
        "CreatePortal": {"method": "POST", "requestUri": "/portals"}}},
    "api-v2": {"credentialScope": "api", "operations": {
        "TagResource": {"method": "POST", "requestUri": "/v2/tags/{arn+}"},
        "GetStage": {"method": "GET", "requestUri": "/v2/apis/{id}/stages/{name}"}}},
    "hand": {"credentialScope": "hand", "operations": {"GetThing": {"method": "GET", "requestUri": "/things"}}}}}


def test_the_route_methods_are_each_routed_operations_alternatives_included():
    assert r.route_methods(METHOD_ROUTES) == {"api": {
        "CreateApi": ["POST"], "CreatePortal": ["POST"], "GetStage": ["GET"], "TagResource": ["POST", "PUT"]}}


def test_an_operation_listing_the_action_of_each_of_its_routes_methods_is_authorized_by_method():
    # TagResource is a PUT in one model and a POST in the other, and lists both beside the resource's PATCH;
    # GetStage, listed for two SDK clients, is no single action, but each client's route is a GET.
    documents = [by_method_service({"TagResource": ["api:PATCH", "api:POST", "api:PUT"],
                                    "GetStage": ["api:GET"],
                                    "CreateApi": ["api:POST", "api:UpdatePolicy", "iam:PassRole"]},
                                   {"GetStage": ["api", "apiv2"]})]
    methods = r.route_methods(METHOD_ROUTES)
    assert r.authorized_by_method(documents, methods, r.authorized_actions_from(documents)) == {
        "api": ["CreateApi", "GetStage", "TagResource"]}


def test_an_operation_missing_the_action_of_one_of_its_routes_methods_keeps_its_own_name():
    documents = [by_method_service({"TagResource": ["api:PATCH", "api:PUT"], "GetStage": ["api:GET"]},
                                   {"GetStage": ["api", "apiv2"]})]
    assert r.authorized_by_method(documents, r.route_methods(METHOD_ROUTES), {}) == {"api": ["GetStage"]}


def test_an_operation_with_an_action_of_its_own_name_keeps_it():
    documents = [by_method_service({"CreatePortal": ["api:CreatePortal", "api:POST"]})]
    assert r.authorized_by_method(documents, r.route_methods(METHOD_ROUTES), {}) == {}


def test_an_operation_the_one_action_shape_names_is_not_also_authorized_by_method():
    documents = [by_method_service({"CreateApi": ["api:POST"]})]
    renamed = r.authorized_actions_from(documents)
    assert renamed == {"api": {"CreateApi": "api:POST"}}
    assert r.authorized_by_method(documents, r.route_methods(METHOD_ROUTES), renamed) == {}


def test_an_operation_listed_twice_must_list_its_methods_both_times():
    documents = [by_method_service({"CreateApi": ["api:POST", "iam:PassRole"]})]
    documents[0]["Operations"].append({"Name": "CreateApi", "AuthorizedActions": [{"Service": "api", "Name": "PUT"}]})
    assert r.authorized_by_method(documents, r.route_methods(METHOD_ROUTES), {}) == {}


def test_a_scope_authorized_by_method_the_reference_does_not_have_is_an_error():
    with pytest.raises(ValueError, match="no namespace api"):
        r.authorized_by_method([typed("other", {})], r.route_methods(METHOD_ROUTES), {})


def test_the_operations_authorized_by_method_must_be_routed_sorted_and_named_once():
    documents = ANCHOR_SERVICES + [by_method_service({"TagResource": ["api:POST", "api:PUT"]}), typed("other", {})]
    methods = r.route_methods(METHOD_ROUTES)
    document = r.build(documents, "a test", ["api"], methods)
    assert document["authorizedByMethod"] == {"api": ["TagResource"]}
    assert r.validate(document, ["api"], methods) == []
    assert "authorizedByMethod lists api, a scope rest-routes.json reads no routes for" in r.validate(document, [])
    document["authorizedByMethod"]["api"] = ["TagResource", "Missing"]
    problems = r.validate(document, ["api"], methods)
    assert "authorizedByMethod.api is not sorted, or repeats an operation" in problems
    assert "authorizedByMethod lists api:Missing, which no route of api serves" in problems
    document["authorizedByMethod"]["api"] = ["TagResource"]
    document["authorizedActions"]["api"] = {"TagResource": "api:PUT"}
    document["authorizedActions"] = dict(sorted(document["authorizedActions"].items()))
    assert "api:TagResource is authorized by method and as api:PUT" in r.validate(document, ["api"], methods)
    document["authorizedByMethod"] = {"api": []}
    assert "authorizedByMethod.api is not a list of operation names" in r.validate(document, ["api"], methods)
    del document["authorizedByMethod"]
    assert "authorizedByMethod is missing" in r.validate(document, ["api"], methods)


def test_verify_fails_on_an_operation_aws_no_longer_authorizes_by_method_and_reports_one_it_added():
    untrue, added = r.verify_by_method_against({"api": ["CreateApi", "TagResource"]}, {"api": ["GetStage", "TagResource"]})
    assert untrue == ["AWS no longer authorizes api:CreateApi by the methods of its routes"]
    assert added == ["api:GetStage by method"]
    assert r.verify_by_method_against({"api": ["TagResource"]}, {"api": ["TagResource"]}) == ([], [])


def test_the_round_trip_lists_the_operations_authorized_by_method(tmp_path):
    reference = by_method_service({"TagResource": ["api:PATCH", "api:POST", "api:PUT"], "GetStage": ["api:GET"]},
                                  {"GetStage": ["api", "apiv2"]})
    sources = saved_sources(tmp_path, ANCHOR_SERVICES + [reference])
    routes = tmp_path / "rest-routes.json"
    routes.write_text(json.dumps(METHOD_ROUTES), encoding="utf-8")
    output = tmp_path / "iam-authorized-actions.json"
    assert r.main(["--source-dir", str(sources), "--output", str(output), "--routes", str(routes)]) == 0
    assert json.loads(output.read_text())["authorizedByMethod"] == {"api": ["GetStage", "TagResource"]}
    assert r.main(["--check", "--output", str(output), "--routes", str(routes)]) == 0
    assert r.main(["--verify", "--source-dir", str(sources), "--output", str(output), "--routes", str(routes)]) == 0
    (tmp_path / "later").mkdir()
    later = by_method_service({"TagResource": ["api:PATCH", "api:PUT"], "GetStage": ["api:GET"]},
                              {"GetStage": ["api", "apiv2"]})
    given = saved_sources(tmp_path / "later", ANCHOR_SERVICES + [later])
    assert r.main(["--verify", "--source-dir", str(given), "--output", str(output), "--routes", str(routes)]) == 1


# --------------------------------------------------------------------------- #
# The S3 user guide's required actions, the namesake client and spellings
# --------------------------------------------------------------------------- #
S3_MODELS = {
    "s3": {"credentialScope": "s3", "operations": {
        "GetPublicAccessBlock": {"method": "GET", "requestUri": "/{Bucket}?publicAccessBlock"},
        "PutPublicAccessBlock": {"method": "PUT", "requestUri": "/{Bucket}?publicAccessBlock"}}},
    "s3control": {"credentialScope": "s3", "operations": {
        "GetPublicAccessBlock": {"method": "GET", "requestUri": "/v20180820/configuration/publicAccessBlock"},
        "PutPublicAccessBlock": {"method": "PUT", "requestUri": "/v20180820/configuration/publicAccessBlock"},
        "GetAccessPointPolicy": {"method": "GET", "requestUri": "/v20180820/accesspoint/{name}/policy"}}},
}
VERSION_SENTENCES = {
    "s3:GetObject": "Required if you want to get an object without specifying versionId in the request.",
    "s3:GetObjectVersion": "Required if you want to get a specific version of an object by specifying versionId "
                           "in the request.",
}
S3_PAGE = {
    "ListObjectsV2": {"section": "Bucket operations and permissions", "required": ["s3:ListBucket"]},
    "CopyObject": {"section": "Object operations and permissions", "required": ["s3:PutObject"],
                   "either": {"actions": ["s3:GetObject", "s3:GetObjectVersion"], "sentences": VERSION_SENTENCES}},
    "GetObject": {"section": "Object operations and permissions",
                  "either": {"actions": ["s3:GetObject", "s3:GetObjectVersion"], "sentences": VERSION_SENTENCES}},
    "GetPublicAccessBlock (Bucket-level)": {"section": "Bucket operations and permissions",
                                            "required": ["s3:GetBucketPublicAccessBlock"]},
    "PutPublicAccessBlock (Account-level)": {"section": "Account operations and permissions",
                                             "required": ["s3:PutAccountPublicAccessBlock"]},
    # The page's own error: S3 Control's account-level GetPublicAccessBlock, headed as another operation.
    "GetAccessPointPolicy (Account-level)": {"section": "Account operations and permissions",
                                             "required": ["s3:GetAccountPublicAccessBlock"]},
    # A row several operations share is not one operation's.
    "CreateStorageLensGroup, TagResource": {"section": "Account operations and permissions",
                                            "required": ["s3:CreateStorageLensGroup", "s3:TagResource"]},
}
# The query parameters botocore gives each operation's request (`s3_query_parameters`).
S3_QUERY = {"GetObject": ["partNumber", "versionId"], "CopyObject": [], "DeleteObjects": [],
            **{operation: ["versionId"] for operation in (
                "GetObjectAnnotation", "PutObjectAnnotation", "DeleteObjectAnnotation", "ListObjectAnnotations")}}


def test_the_s3_user_guide_gives_an_operation_its_one_required_action():
    actions, _, _ = r.s3_required(S3_PAGE, S3_MODELS, S3_QUERY)
    assert actions == {"CopyObject": "s3:PutObject", "ListObjectsV2": "s3:ListBucket"}


def test_an_operation_both_models_have_takes_the_pages_action_for_one_models_requests():
    _, by_model, _ = r.s3_required(S3_PAGE, S3_MODELS, S3_QUERY)
    # The Account-level row is S3 Control's, any other S3's. GetAccessPointPolicy is S3 Control's alone, so its level
    # tells nothing apart; the measured entry gives the account-level GetPublicAccessBlock the page mislabels.
    assert by_model == {"s3": {
        "s3": {"GetPublicAccessBlock": "s3:GetBucketPublicAccessBlock"},
        "s3control": {"GetPublicAccessBlock": "s3:GetAccountPublicAccessBlock",
                      "PutPublicAccessBlock": "s3:PutAccountPublicAccessBlock"}}}


def test_an_either_row_is_a_choice_by_the_parameter_its_sentences_name_unless_the_operation_requires_its_own():
    _, _, versioned = r.s3_required(S3_PAGE, S3_MODELS, S3_QUERY)
    assert versioned["s3"]["GetObject"] == {"parameter": "versionId", "without": "s3:GetObject",
                                            "with": "s3:GetObjectVersion"}
    # CopyObject's own action is s3:PutObject; its Either is about the source it copies.
    assert "CopyObject" not in versioned["s3"]
    assert versioned["s3"]["GetObjectAnnotation"]["with"] == "s3:GetObjectVersionAnnotation"


def test_a_version_choice_is_read_only_where_botocore_gives_its_parameter_in_the_query():
    deletes = {"section": "Object operations and permissions",
               "either": {"actions": ["s3:DeleteObject", "s3:DeleteObjectVersion"], "sentences": {
                   "s3:DeleteObject": "Required if you want to remove an object without specifying versionId in the "
                                      "request.",
                   "s3:DeleteObjectVersion": "Required if you want to remove a specific version of an object by "
                                             "specifying versionId in the request."}}}
    # DeleteObjects names each version in its body, so a request's query never says which it deletes.
    _, _, versioned = r.s3_required({**S3_PAGE, "DeleteObjects": deletes}, S3_MODELS, S3_QUERY)
    assert "DeleteObjects" not in versioned["s3"]
    assert "GetObject" in versioned["s3"]


def test_every_version_choice_is_listed_wherever_the_request_names_the_version():
    deletes = {"section": "Object operations and permissions",
               "either": {"actions": ["s3:DeleteObject", "s3:DeleteObjectVersion"], "sentences": {
                   "s3:DeleteObject": "Required if you want to remove an object without specifying versionId in the "
                                      "request.",
                   "s3:DeleteObjectVersion": "Required if you want to remove a specific version of an object by "
                                             "specifying versionId in the request."}}}
    choices = r.s3_version_choices({**S3_PAGE, "DeleteObjects": deletes})
    # DeleteObjects names its versions in its body, so it has no query choice, but its choice is still the page's.
    assert choices["DeleteObjects"] == {"parameter": "versionId", "without": "s3:DeleteObject",
                                        "with": "s3:DeleteObjectVersion"}
    assert choices["GetObject"]["with"] == "s3:GetObjectVersion"
    # CopyObject requires an action of its own, so its Either row is about its source, not its version.
    assert "CopyObject" not in choices


def test_a_measured_version_choice_by_a_parameter_botocore_gives_no_query_is_refused():
    with pytest.raises(ValueError):
        r.s3_required(S3_PAGE, S3_MODELS, {**S3_QUERY, "GetObjectAnnotation": []})


def test_an_account_level_row_whose_s3_control_route_names_a_resource_is_refused():
    named = {**S3_MODELS, "s3control": {"credentialScope": "s3", "operations": {
        **S3_MODELS["s3control"]["operations"],
        "PutPublicAccessBlock": {"method": "PUT", "requestUri": "/v20180820/bucket/{name}/publicAccessBlock"}}}}
    with pytest.raises(ValueError):
        r.s3_required(S3_PAGE, named, S3_QUERY)


def test_the_query_parameters_are_read_from_botocores_s3_model(tmp_path):
    directory = tmp_path / "s3" / "2006-03-01"
    directory.mkdir(parents=True)
    (directory / "service-2.json").write_text(json.dumps({
        "operations": {"GetObject": {"input": {"shape": "GetObjectRequest"}},
                       "DeleteObjects": {"input": {"shape": "DeleteObjectsRequest"}}},
        "shapes": {"GetObjectRequest": {"members": {
                       "Bucket": {"location": "uri", "locationName": "Bucket"},
                       "VersionId": {"location": "querystring", "locationName": "versionId"}}},
                   "DeleteObjectsRequest": {"members": {"Delete": {"locationName": "Delete"}}}}}), encoding="utf-8")
    assert r.s3_query_parameters(tmp_path) == {"GetObject": ["versionId"], "DeleteObjects": []}


def test_the_vendored_sections_are_assembled_in_one_place():
    s3 = with_sdk_clients(service("s3", {"GetObject": ["s3:GetObject", "s3:GetObjectVersion"]},
                                  ["GetObject", "GetObjectVersion"]), "s3", "s3control")
    memorydb = service("memorydb", {"CreateACL": ["memorydb:CreateAcl"]}, ["CreateAcl"])
    sections = r.vendored_sections([s3, memorydb], S3_PAGE, S3_MODELS, S3_QUERY, {})
    assert set(sections) == {"authorizedByModel", "versioned", "spellings"}
    assert sections["versioned"]["s3"]["GetObject"]["with"] == "s3:GetObjectVersion"
    assert sections["authorizedByModel"]["s3"]["s3control"]["PutPublicAccessBlock"] == "s3:PutAccountPublicAccessBlock"
    assert sections["spellings"] == {"memorydb": {"CreateACL": "memorydb:CreateAcl"}}
    # A rename already gives the spelling, so the section does not repeat it.
    assert r.vendored_sections([memorydb], S3_PAGE, S3_MODELS, S3_QUERY,
                               {"memorydb": {"CreateACL": "memorydb:CreateAcl"}})["spellings"] == {"memorydb": {}}


def test_a_measured_entry_with_no_link_or_one_the_page_gives_is_refused(monkeypatch):
    with pytest.raises(ValueError):
        r.s3_required({**S3_PAGE, "GetPublicAccessBlock (Account-level)": {
            "section": "Account operations and permissions", "required": ["s3:GetAccountPublicAccessBlock"]}},
            S3_MODELS, S3_QUERY)
    monkeypatch.setattr(r, "MEASURED_BY_MODEL", {("s3", "s3control", "GetPublicAccessBlock"): (
        "s3:GetAccountPublicAccessBlock", "the actions table says so")})
    with pytest.raises(ValueError):
        r.s3_required(S3_PAGE, S3_MODELS, S3_QUERY)


def test_the_page_answers_an_s3_operation_the_reference_does_not_and_a_disagreement_fails():
    s3 = service("s3", {"ListObjectsV2": ["s3:ListBucket", "s3:GetObjectAcl"], "CopyObject": ["s3:PutObject"]},
                 ["ListBucket", "GetObjectAcl", "PutObject"])
    assert r.authorized_actions([s3], S3_PAGE, S3_MODELS, S3_QUERY) == {
        "s3": {"CopyObject": "s3:PutObject", "ListObjectsV2": "s3:ListBucket"}}
    disagreeing = service("s3", {"ListObjectsV2": ["s3:GetObjectAcl"]}, ["ListBucket", "GetObjectAcl"])
    # An operation both models have is answered for S3's requests alone, not in the map every model's request reads.
    shared = service("s3", {"GetPublicAccessBlock": ["s3:GetBucketPublicAccessBlock"]}, ["GetBucketPublicAccessBlock"])
    assert r.authorized_actions([with_sdk_clients(shared, "s3", "s3control")], S3_PAGE, S3_MODELS, S3_QUERY) == {}
    with pytest.raises(ValueError):
        r.authorized_actions([disagreeing], S3_PAGE, S3_MODELS, S3_QUERY)
    # References without S3's read nothing of the page.
    assert r.authorized_actions(ANCHOR_SERVICES, S3_PAGE, S3_MODELS, S3_QUERY) == r.authorized_actions_from(ANCHOR_SERVICES)


def test_the_namesake_client_takes_the_one_answer_of_an_operation_several_clients_list():
    lifecycle = with_sdk_clients(service("s3", {"GetBucketLifecycleConfiguration": [
        "s3:GetLifecycleConfiguration", "s3express:GetLifecycleConfiguration"]}, ["GetLifecycleConfiguration"]),
        "s3", "s3control")
    assert r.namesake_by_model([lifecycle], {"s3", "s3control"}) == {
        "s3": {"s3": {"GetBucketLifecycleConfiguration": "s3:GetLifecycleConfiguration"}}}
    # A client whose routes are not vendored cannot be told from its request's route.
    assert r.namesake_by_model([lifecycle], {"s3"}) == {}


def test_an_action_spelled_otherwise_than_its_operation_keeps_the_references_spelling():
    memorydb = service("memorydb", {"CreateACL": ["memorydb:CreateAcl"], "CreateCluster": ["memorydb:CreateCluster"]},
                       ["CreateAcl", "CreateCluster"])
    assert r.spellings([memorydb]) == {"memorydb": {"CreateACL": "memorydb:CreateAcl"}}


def test_the_new_sections_are_built_only_for_references_with_s3_and_validated():
    document = r.build(ANCHOR_SERVICES, "a test", [], page=S3_PAGE, models=S3_MODELS)
    assert document["authorizedByModel"] == {} and document["versioned"] == {}
    assert r.validate(document, []) == []
    broken = dict(document, authorizedByModel={"s3": {"s3control": {"GetPublicAccessBlock": "sqs:SendMessage"}}},
                  versioned={"s3": {"GetObject": {"parameter": "versionId", "without": "s3:GetObject"}}},
                  spellings={"memorydb": {"CreateACL": "memorydb:CreateCluster"}})
    problems = r.validate(broken, [])
    assert any("authorizedByModel" in problem for problem in problems)
    assert any("versioned" in problem for problem in problems)
    assert any("spellings" in problem for problem in problems)
