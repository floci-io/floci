"""Tests for regen_service_namespaces.

Run with: pytest tools/aws -q  (or: make iam-namespaces-test)
"""
from __future__ import annotations

import json

import pytest

import regen_service_namespaces as r


def index(*services: str) -> list[dict]:
    return [{"service": name, "url": f"https://example.test/{name}.json"} for name in services]


# --------------------------------------------------------------------------- #
# namespaces_from: what the index is allowed to contain
# --------------------------------------------------------------------------- #
def test_namespaces_are_sorted_and_deduplicated():
    assert r.namespaces_from(index("s3", "cloudwatch", "s3", "iam")) == ["cloudwatch", "iam", "s3"]


def test_hyphens_and_digits_are_accepted():
    assert r.namespaces_from(index("execute-api", "s3", "route53domains", "a4b")) == [
        "a4b", "execute-api", "route53domains", "s3"]


@pytest.mark.parametrize("payload, reason", [
    ([], "empty"),
    ({"service": "s3"}, "not a list"),
    ([{"url": "x"}], "no service field"),
])
def test_malformed_index_is_rejected(payload, reason):
    with pytest.raises(ValueError):
        r.namespaces_from(payload)


@pytest.mark.parametrize("namespace", [
    "S3",             # serviceNamespaceType is lowercase in practice
    "cloud watch",    # a space is not usable in an action prefix
    "cloud_watch",    # underscores do not appear in AWS namespaces
    "-leading",       # must start with a letter or digit
    "a" * 65,         # serviceNamespaceType is max 64
    "",
])
def test_unusable_namespace_is_rejected(namespace):
    with pytest.raises(ValueError):
        r.namespaces_from([{"service": namespace}])


# --------------------------------------------------------------------------- #
# validate: the offline gate over the vendored file
# --------------------------------------------------------------------------- #
def sound() -> list[str]:
    return sorted(["cloudwatch", "logs", "iam", "s3", "sts"])


def test_a_sound_document_has_no_problems():
    assert r.validate({"serviceNamespaces": sound()}) == []


def test_unsorted_is_reported():
    assert "serviceNamespaces is not sorted" in r.validate(
        {"serviceNamespaces": ["s3", "cloudwatch", "iam", "logs", "sts"]})


def test_duplicates_are_reported():
    assert "serviceNamespaces contains duplicates" in r.validate(
        {"serviceNamespaces": sorted(sound() + ["s3"])})


def test_missing_or_empty_is_reported():
    assert r.validate({}) == ["serviceNamespaces is missing or empty"]
    assert r.validate({"serviceNamespaces": []}) == ["serviceNamespaces is missing or empty"]


def test_a_namespace_botocore_would_have_produced_is_rejected():
    """'monitoring' is CloudWatch's credential scope; using it as an IAM namespace is the bug
    this whole file exists to prevent, so the gate names it explicitly."""
    problems = r.validate({"serviceNamespaces": sorted(sound() + ["monitoring"])})
    assert any("credential scope" in p for p in problems)


def test_losing_a_sentinel_namespace_is_reported():
    problems = r.validate({"serviceNamespaces": ["iam", "logs", "s3", "sts"]})
    assert any("cloudwatch" in p for p in problems)


# --------------------------------------------------------------------------- #
# verify_against: the online gate, which is what stops invented data
# --------------------------------------------------------------------------- #
def test_nothing_invented_and_nothing_added():
    invented, added = r.verify_against(["iam", "s3"], ["iam", "s3"])
    assert invented == [] and added == []


def test_an_invented_namespace_is_caught():
    invented, added = r.verify_against(["iam", "s3", "s3-typo"], ["iam", "s3"])
    assert invented == ["s3-typo"]
    assert added == []


def test_a_namespace_aws_retired_is_caught():
    invented, _ = r.verify_against(["iam", "retired-service"], ["iam"])
    assert invented == ["retired-service"]


def test_services_aws_added_are_reported_but_not_invented():
    """A vendored file that is merely behind makes the report incomplete, not wrong, so this must
    not fail the build: AWS shipping a service is not a defect here."""
    invented, added = r.verify_against(["iam", "s3"], ["brand-new", "iam", "s3"])
    assert invented == []
    assert added == ["brand-new"]


# --------------------------------------------------------------------------- #
# build / render: the vendored file itself
# --------------------------------------------------------------------------- #
def test_build_records_provenance_and_namespaces():
    document = r.build(index("s3", "cloudwatch", "logs", "iam", "sts"), "https://example.test/")
    assert document["_source"]["index"] == "https://example.test/"
    assert document["_source"]["generator"] == "tools/aws/regen_service_namespaces.py"
    assert document["serviceNamespaces"] == ["cloudwatch", "iam", "logs", "s3", "sts"]


def test_render_is_stable_json_with_a_trailing_newline():
    document = r.build(index("s3", "iam"), "x")
    text = r.render(document)
    assert text.endswith("\n")
    assert json.loads(text) == document


def test_check_accepts_the_vendored_file_in_this_repo():
    """The file actually committed here must pass the offline gate."""
    vendored = json.loads(r.OUTPUT.read_text(encoding="utf-8"))
    assert r.validate(vendored) == []
    assert len(vendored["serviceNamespaces"]) > 300, "AWS publishes several hundred namespaces"
