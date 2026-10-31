"""Tests for regen_iam_passed_network_connectors.

Run with: pytest tools/aws -q  (or: make iam-passed-network-connectors-test)
"""
from __future__ import annotations

import json

import pytest

import regen_iam_passed_network_connectors as r


def reference(namespace: str, operations: dict[str, bool], clients: dict[str, list[str]] | None = None) -> dict:
    """A Service Reference document. Each operation maps to whether it lists lambda:PassNetworkConnector;
    `clients` gives an operation's SDK clients, the namespace by default."""
    entries = []
    for name, passes in operations.items():
        authorized = [{"Service": namespace, "Name": name}]
        if passes:
            authorized.append({"Service": "lambda", "Name": "PassNetworkConnector"})
        sdk = [{"Name": c, "Method": name, "Package": "Boto3"} for c in (clients or {}).get(name, [namespace])]
        entries.append({"Name": name, "AuthorizedActions": authorized, "SDK": sdk})
    return {"Name": namespace, "Operations": entries}


def model(operations: dict[str, str], shapes: dict[str, dict], protocol: str = "rest-json") -> dict:
    """A botocore model whose operations take the input shape each names."""
    return {"metadata": {"protocol": protocol},
            "operations": {name: {"input": {"shape": shape}} for name, shape in operations.items()},
            "shapes": {"String": {"type": "string"}, **shapes}}


def structure(**members) -> dict:
    return {"type": "structure", "members": {name: ({"shape": spec} if isinstance(spec, str) else spec)
                                             for name, spec in members.items()}}


CONNECTORS = {"type": "list", "member": {"shape": "String"}}


@pytest.fixture
def corrections(monkeypatch):
    """No corrections at all, so a test sees the rule alone and sets what it needs."""
    monkeypatch.setattr(r, "WHEN_ABSENT", {})
    monkeypatch.setattr(r, "NOT_READ", {})
    return monkeypatch


# --------------------------------------------------------------------------- #
# The Service Reference
# --------------------------------------------------------------------------- #
def test_only_operations_listing_the_action_are_kept_with_their_clients():
    found = r.passed_connector_operations([
        reference("lambda", {"RunMicrovm": True, "GetMicrovm": False}, {"RunMicrovm": ["lambda-microvms"]}),
        reference("appsync", {"CreateDataSource": True})])
    assert found == {"appsync:CreateDataSource": {"clients": ["appsync"]},
                     "lambda:RunMicrovm": {"clients": ["lambda-microvms"]}}


def test_pass_role_alone_does_not_count():
    document = reference("svc", {"CreateThing": False})
    document["Operations"][0]["AuthorizedActions"].append({"Service": "iam", "Name": "PassRole"})
    assert r.passed_connector_operations([document]) == {}


def test_an_unusable_namespace_is_rejected():
    with pytest.raises(ValueError, match="not usable"):
        r.passed_connector_operations([{"Name": "Not A Namespace", "Operations": []}])


# --------------------------------------------------------------------------- #
# botocore: the members that name a connector
# --------------------------------------------------------------------------- #
def test_a_member_is_a_connector_by_its_name_when_it_holds_a_string_or_a_list_of_them():
    m = model({"Op": "In"}, {
        "In": structure(egressNetworkConnectorArn="String", ingressNetworkConnectors="Connectors",
                        NetworkConnector="String", networkConnectorType="String", ConnectorArn="String",
                        connectorKind="Kind"),
        "Connectors": CONNECTORS,
        "Kind": {"type": "string", "enum": ["VPC_EGRESS"]},
    })
    assert r.connector_members(m, "Op") == ["NetworkConnector", "egressNetworkConnectorArn",
                                            "ingressNetworkConnectors[]"]


def test_an_enum_names_no_connector():
    m = model({"Op": "In"}, {"In": structure(networkConnector="Kind"),
                             "Kind": {"type": "string", "enum": ["VPC_EGRESS"]}})
    assert r.connector_members(m, "Op") == []


def test_a_nested_member_is_written_as_its_path():
    m = model({"Op": "In"}, {"In": structure(revisionConfig="Revision"),
                             "Revision": structure(serviceConfig="Service"),
                             "Service": structure(egressNetworkConnectorArn="String")})
    assert r.connector_members(m, "Op") == ["revisionConfig.serviceConfig.egressNetworkConnectorArn"]


def test_a_member_bound_to_the_uri_is_not_read():
    m = model({"Op": "In"}, {"In": structure(networkConnectorArn={"shape": "String", "location": "uri"})})
    assert r.connector_members(m, "Op") == []


# --------------------------------------------------------------------------- #
# One operation's entry
# --------------------------------------------------------------------------- #
RUN = {"lambda-microvms": model({"RunMicrovm": "In"}, {
    "In": structure(ingressNetworkConnectors="Connectors", egressNetworkConnectors="Connectors"),
    "Connectors": CONNECTORS})}


def test_the_members_are_sorted_with_the_connector_checked_when_one_is_absent(corrections):
    corrections.setattr(r, "WHEN_ABSENT", {"lambda:RunMicrovm": {
        "ingressNetworkConnectors[]": "aws-network-connector:HTTP_INGRESS"}})
    assert r.entry_for("lambda:RunMicrovm", RUN) == {"connectors": [
        {"member": "egressNetworkConnectors[]"},
        {"member": "ingressNetworkConnectors[]", "whenAbsent": "aws-network-connector:HTTP_INGRESS"}]}


def test_a_fallback_for_a_member_the_request_does_not_have_fails(corrections):
    corrections.setattr(r, "WHEN_ABSENT", {"lambda:RunMicrovm": {"missing[]": "aws-network-connector:X"}})
    with pytest.raises(ValueError, match="does not have"):
        r.entry_for("lambda:RunMicrovm", RUN)


def test_an_operation_naming_no_connector_must_say_why(corrections):
    empty = {"appsync": model({"CreateDataSource": "In"}, {"In": structure(name="String")})}
    with pytest.raises(ValueError, match="NOT_READ"):
        r.entry_for("appsync:CreateDataSource", empty)
    corrections.setattr(r, "NOT_READ", {"appsync:CreateDataSource": "no member names a connector"})
    assert r.entry_for("appsync:CreateDataSource", empty) == {"notRead": "no member names a connector"}


def test_an_unread_operation_whose_request_gains_a_connector_fails(corrections):
    corrections.setattr(r, "NOT_READ", {"lambda:RunMicrovm": "no member names a connector"})
    with pytest.raises(ValueError, match="NOT_READ says"):
        r.entry_for("lambda:RunMicrovm", RUN)


# --------------------------------------------------------------------------- #
# The whole document
# --------------------------------------------------------------------------- #
def write_models(root, models: dict[str, dict]):
    for name, value in models.items():
        directory = root / name / "2020-01-01"
        directory.mkdir(parents=True)
        (directory / "service-2.json").write_text(json.dumps(value), encoding="utf-8")


def test_a_built_document_validates_and_matches_botocore(corrections, tmp_path):
    write_models(tmp_path, RUN)
    document = r.build([reference("lambda", {"RunMicrovm": True}, {"RunMicrovm": ["lambda-microvms"]})],
                       "test", tmp_path, "botocore test")
    assert r.validate(document) == []
    assert r.derive(document, tmp_path) == []
    assert document["namespaces"] == {"lambda": ["lambda-microvms"]}
    assert json.loads(r.render(document)) == document


def test_a_hand_edited_member_does_not_match_botocore(corrections, tmp_path):
    write_models(tmp_path, RUN)
    document = r.build([reference("lambda", {"RunMicrovm": True}, {"RunMicrovm": ["lambda-microvms"]})],
                       "test", tmp_path, "botocore test")
    document["operations"]["lambda:RunMicrovm"]["connectors"].append({"member": "other"})
    assert r.validate(document) == []
    assert len(r.derive(document, tmp_path)) == 1


def test_a_correction_naming_an_operation_the_reference_does_not_list_fails(corrections, tmp_path):
    write_models(tmp_path, RUN)
    corrections.setattr(r, "NOT_READ", {"lambda:Gone": "no member names a connector"})
    with pytest.raises(ValueError, match="lambda:Gone"):
        r.build([reference("lambda", {"RunMicrovm": True}, {"RunMicrovm": ["lambda-microvms"]})],
                "test", tmp_path, "botocore test")


def good_document() -> dict:
    return {"namespaces": {"lambda": ["lambda-web"]}, "operations": {
        "lambda:A": {"connectors": [{"member": "egress[]", "whenAbsent": "aws-network-connector:INTERNET_EGRESS"},
                                    {"member": "serviceConfig.egressNetworkConnectorArn"}]},
        "lambda:B": {"notRead": "no member names a connector"}}}


def test_a_well_formed_document_validates():
    assert r.validate(good_document()) == []


@pytest.mark.parametrize("change, problem", [
    (lambda d: d["operations"]["lambda:A"].update(notRead="x"), "exactly one"),
    (lambda d: d["operations"]["lambda:A"]["connectors"][1].update(member="a..b"), "no member path"),
    (lambda d: d["operations"]["lambda:A"]["connectors"].reverse(), "sorted order"),
    (lambda d: d["operations"]["lambda:A"]["connectors"][0].update(whenAbsent="my-connector"), "Lambda-managed"),
    (lambda d: d["operations"]["lambda:A"]["connectors"][0].update(passedTo="lambda"), "unknown field"),
    (lambda d: d["operations"].update({"other:C": {"notRead": "x"}}), "namespace the file reads"),
])
def test_a_malformed_document_is_rejected(change, problem):
    document = good_document()
    change(document)
    assert any(problem in p for p in r.validate(document)), r.validate(document)


def test_verify_fails_on_a_claim_aws_does_not_make_and_reports_an_added_operation():
    upstream = {"lambda:A": {"clients": ["lambda-web", "lambda-microvms"]},
                "lambda:D": {"clients": ["lambda-web"]}}
    untrue, added = r.verify_against(good_document(), upstream)
    assert untrue == ["AWS lists lambda:A for ['lambda-web', 'lambda-microvms'], which the file does not read",
                      "AWS no longer lists lambda:PassNetworkConnector for lambda:B"]
    assert added == ["lambda:D"]


def test_the_vendored_file_is_well_formed():
    document = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(document) == []
    assert document["operations"]["lambda:CreateWebFunctionRevision"]["connectors"] == [
        {"member": "serviceConfig.egressNetworkConnectorArn"}]
    assert [c["member"] for c in document["operations"]["lambda:RunMicrovm"]["connectors"]] == [
        "egressNetworkConnectors[]", "ingressNetworkConnectors[]"]
