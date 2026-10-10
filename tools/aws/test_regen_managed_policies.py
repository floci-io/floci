"""Tests for regen_managed_policies.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

import regen_managed_policies as m

READ_ONLY = {"Statement": [{"Action": "s3:Get*", "Effect": "Allow", "Resource": "*"}], "Version": "2012-10-17"}
SERVICE_ROLE = {"Statement": [{"Action": "ec2:Describe*", "Effect": "Allow", "Resource": "*"}], "Version": "2012-10-17"}
NEW_POLICY = {"Statement": [{"Action": "sqs:*", "Effect": "Allow", "Resource": "*"}], "Version": "2012-10-17"}
LEGACY = {"Statement": [{"Action": "lambda:*", "Effect": "Allow", "Resource": "*"}], "Version": "2012-10-17"}


def write_dataset(root: Path, policies: list[dict], documents: dict[str, dict]) -> Path:
    (root / "aws/managedpolicies").mkdir(parents=True)
    (root / "aws/managed_policies.json").write_text(json.dumps({"policies": policies}))
    for name, document in documents.items():
        (root / f"aws/managedpolicies/{name}.json").write_text(json.dumps({"name": name, "document": document}))
    return root


def policy(name: str, path: str = "/", version: str = "v1", created: str = "2020-01-01T00:00:00+00:00",
           updated: str | None = "2020-01-01T00:00:00+00:00", deprecated: bool = False) -> dict:
    return {"name": name, "arn": None if deprecated else f"arn:aws:iam::aws:policy{path}{name}",
            "version": version, "createdate": created, "updatedate": updated, "deprecated": deprecated}


@pytest.fixture
def dataset(tmp_path: Path) -> Path:
    return write_dataset(tmp_path / "iam-dataset", [
        policy("AmazonS3ReadOnlyAccess", version="v3", created="2023-08-10T21:31:39+00:00",
               updated="2023-08-10T21:31:39+00:00"),
        policy("AmazonEC2RoleforSSM", path="/service-role/"),
        policy("AWSLambdaFullAccess", version="v8", created="2017-11-27T23:22:38Z", updated=None, deprecated=True),
        policy("RetiredPolicy", deprecated=True),
    ], {"AmazonS3ReadOnlyAccess": READ_ONLY, "AmazonEC2RoleforSSM": SERVICE_ROLE,
        "AWSLambdaFullAccess": LEGACY, "RetiredPolicy": LEGACY})


@pytest.fixture
def vendored(tmp_path: Path) -> Path:
    resources = tmp_path / "resources"
    resources.mkdir()
    catalog = [{"name": "AWSLambdaFullAccess", "path": "/"},
               {"name": "AmazonEC2RoleforSSM", "path": "/service-role/"},
               {"name": "AmazonS3ReadOnlyAccess", "path": "/", "description": "Provides read only access to S3."}]
    versions = {"AWSLambdaFullAccess": {"defaultVersionId": "v8", "createDate": "2015-02-06T18:40:00Z",
                                        "updateDate": "2017-11-27T23:22:38Z"},
                "AmazonEC2RoleforSSM": {"defaultVersionId": "v1", "updateDate": "2020-01-01T00:00:00Z"},
                "AmazonS3ReadOnlyAccess": {"defaultVersionId": "v3", "createDate": "2015-02-06T18:40:00Z",
                                           "updateDate": "2023-08-10T21:31:39Z"}}
    documents = {"AWSLambdaFullAccess": LEGACY, "AmazonEC2RoleforSSM": SERVICE_ROLE, "AmazonS3ReadOnlyAccess": READ_ONLY}
    for name, text in m.render(catalog, documents, versions).items():
        (resources / name).write_text(text)
    return resources


def generate(dataset: Path, resources: Path, fetch=None) -> dict[str, str]:
    catalog, _, versions = m.read_vendored(resources)
    return m.render(*m.build(dataset, catalog, versions, fetch))


# --------------------------------------------------------------------------- #
# Parsers
# --------------------------------------------------------------------------- #
def test_name_and_path_split_the_arn():
    assert m.name_and_path("arn:aws:iam::aws:policy/service-role/AmazonEC2RoleforSSM") == (
        "AmazonEC2RoleforSSM", "/service-role/")
    assert m.name_and_path("arn:aws:iam::aws:policy/AdministratorAccess") == ("AdministratorAccess", "/")


def test_instant_normalises_offsets_to_z():
    assert m.instant("2026-02-12T17:57:39+00:00") == "2026-02-12T17:57:39Z"
    assert m.instant("2017-11-27T23:22:38Z") == "2017-11-27T23:22:38Z"
    assert m.instant(None) is None


def test_creation_time_is_read_from_the_reference_page():
    html = "<p><b>Creation time</b> : February 06, 2015, 18:40 UTC </p><p>Edited time: August 10, 2023, 21:31 UTC</p>"
    assert m.parse_creation_time(html) == "2015-02-06T18:40:00Z"
    assert m.parse_creation_time("<p>no dates here</p>") is None


def test_catalog_round_trips_through_render_and_parse():
    catalog = [{"name": "A", "path": "/"}, {"name": "B", "path": "/x/", "description": 'Says "hi".'}]
    rendered = m.render_catalog(catalog)
    assert rendered.startswith(m.HEADER)
    assert m.parse_catalog(rendered) == catalog


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #
def test_regeneration_reproduces_the_vendored_files(dataset: Path, vendored: Path):
    for name, text in generate(dataset, vendored).items():
        assert (vendored / name).read_text() == text, name


def test_deprecated_policies_leave_the_catalog_except_the_retained_ones(dataset: Path, vendored: Path):
    catalog, documents, _ = m.build(dataset, *m.read_vendored(vendored)[::2])
    names = [entry["name"] for entry in catalog]
    assert "RetiredPolicy" not in names
    assert "AWSLambdaFullAccess" in names
    assert documents["AWSLambdaFullAccess"] == LEGACY


def test_a_new_v1_policy_takes_the_dataset_date_as_its_creation_date(tmp_path: Path, vendored: Path):
    dataset = write_dataset(tmp_path / "ds", [
        policy("AmazonS3ReadOnlyAccess", version="v3", updated="2023-08-10T21:31:39+00:00"),
        policy("AmazonEC2RoleforSSM", path="/service-role/"),
        policy("AWSLambdaFullAccess", version="v8", created="2017-11-27T23:22:38Z", updated=None, deprecated=True),
        policy("AmazonSQSFullAccess", created="2026-09-01T10:00:00+00:00", updated="2026-09-01T10:00:00+00:00"),
    ], {"AmazonS3ReadOnlyAccess": READ_ONLY, "AmazonEC2RoleforSSM": SERVICE_ROLE,
        "AWSLambdaFullAccess": LEGACY, "AmazonSQSFullAccess": NEW_POLICY})
    versions = json.loads(generate(dataset, vendored)[m.VERSIONS])
    assert versions["AmazonSQSFullAccess"] == {"defaultVersionId": "v1", "createDate": "2026-09-01T10:00:00Z",
                                               "updateDate": "2026-09-01T10:00:00Z"}


def test_a_new_policy_past_v1_gets_its_creation_date_only_from_the_reference_page(tmp_path: Path, vendored: Path):
    dataset = write_dataset(tmp_path / "ds", [
        policy("AmazonS3ReadOnlyAccess", version="v3", updated="2023-08-10T21:31:39+00:00"),
        policy("AmazonEC2RoleforSSM", path="/service-role/"),
        policy("AWSLambdaFullAccess", version="v8", created="2017-11-27T23:22:38Z", updated=None, deprecated=True),
        policy("AmazonSQSFullAccess", version="v2", updated="2026-09-01T10:00:00+00:00"),
    ], {"AmazonS3ReadOnlyAccess": READ_ONLY, "AmazonEC2RoleforSSM": SERVICE_ROLE,
        "AWSLambdaFullAccess": LEGACY, "AmazonSQSFullAccess": NEW_POLICY})
    offline = json.loads(generate(dataset, vendored)[m.VERSIONS])
    assert "createDate" not in offline["AmazonSQSFullAccess"]
    fetched = []
    online = json.loads(generate(dataset, vendored, lambda name: fetched.append(name) or "2014-11-11T00:00:00Z")[m.VERSIONS])
    assert fetched == ["AmazonSQSFullAccess"]
    assert online["AmazonSQSFullAccess"]["createDate"] == "2014-11-11T00:00:00Z"


def test_a_document_or_version_change_is_picked_up_and_curated_fields_are_kept(tmp_path: Path, vendored: Path):
    revised = {"Statement": [{"Action": ["s3:Get*", "s3:List*"], "Effect": "Allow", "Resource": "*"}],
               "Version": "2012-10-17"}
    dataset = write_dataset(tmp_path / "ds", [
        policy("AmazonS3ReadOnlyAccess", version="v4", updated="2026-09-10T00:00:00+00:00"),
        policy("AmazonEC2RoleforSSM", path="/service-role/"),
        policy("AWSLambdaFullAccess", version="v8", created="2017-11-27T23:22:38Z", updated=None, deprecated=True),
    ], {"AmazonS3ReadOnlyAccess": revised, "AmazonEC2RoleforSSM": SERVICE_ROLE, "AWSLambdaFullAccess": LEGACY})
    out = generate(dataset, vendored)
    assert json.loads(out[m.DOCUMENTS])["AmazonS3ReadOnlyAccess"] == revised
    assert json.loads(out[m.VERSIONS])["AmazonS3ReadOnlyAccess"] == {
        "defaultVersionId": "v4", "createDate": "2015-02-06T18:40:00Z", "updateDate": "2026-09-10T00:00:00Z"}
    assert {"name": "AmazonS3ReadOnlyAccess", "path": "/", "description": "Provides read only access to S3."} in \
        m.parse_catalog(out[m.CATALOG])


def test_a_policy_with_no_document_fails_the_generation(tmp_path: Path, vendored: Path):
    dataset = write_dataset(tmp_path / "ds", [policy("AWSLambdaFullAccess", deprecated=True),
                                              policy("NoDocument")], {"AWSLambdaFullAccess": LEGACY})
    with pytest.raises(ValueError, match="no document for NoDocument"):
        generate(dataset, vendored)


# --------------------------------------------------------------------------- #
# The --check gate
# --------------------------------------------------------------------------- #
def test_check_passes_on_a_fresh_generation(dataset: Path, vendored: Path, capsys):
    assert m.main(["--check", "--dataset", str(dataset), "--resources", str(vendored)]) == 0
    assert "up to date (3 policies)" in capsys.readouterr().out


def test_check_fails_on_a_hand_edit(dataset: Path, vendored: Path, capsys):
    versions = json.loads((vendored / m.VERSIONS).read_text())
    versions["AmazonS3ReadOnlyAccess"]["defaultVersionId"] = "v9"
    (vendored / m.VERSIONS).write_text(m.render_versions(versions))
    assert m.main(["--check", "--dataset", str(dataset), "--resources", str(vendored)]) == 1
    assert "differ from a fresh generation" in capsys.readouterr().out


def test_check_without_the_dataset_verifies_shape_only(tmp_path: Path, vendored: Path, capsys):
    assert m.main(["--check", "--dataset", str(tmp_path / "absent"), "--resources", str(vendored)]) == 0
    assert "shape verified only" in capsys.readouterr().out


def test_shape_check_catches_files_that_disagree(vendored: Path):
    documents = json.loads((vendored / m.DOCUMENTS).read_text())
    del documents["AmazonEC2RoleforSSM"]
    (vendored / m.DOCUMENTS).write_text(m.render_documents(documents))
    assert any("no entry for ['AmazonEC2RoleforSSM']" in p for p in m.self_check(vendored))


def test_shape_check_catches_malformed_values(vendored: Path):
    versions = json.loads((vendored / m.VERSIONS).read_text())
    versions["AmazonS3ReadOnlyAccess"]["defaultVersionId"] = "3"
    versions["AmazonEC2RoleforSSM"]["updateDate"] = "yesterday"
    (vendored / m.VERSIONS).write_text(m.render_versions(versions))
    problems = m.self_check(vendored)
    assert any("AmazonS3ReadOnlyAccess has a malformed defaultVersionId" in p for p in problems)
    assert any("AmazonEC2RoleforSSM.updateDate is not an ISO-8601 instant" in p for p in problems)


def test_shape_check_catches_an_entry_out_of_the_generated_layout(vendored: Path):
    text = (vendored / m.CATALOG).read_text().replace('    path: "/service-role/"\n', "    path: /service-role/\n")
    (vendored / m.CATALOG).write_text(text)
    assert any("do not have the generated layout" in p for p in m.self_check(vendored))


def test_the_vendored_catalog_passes_the_shape_check():
    assert m.self_check(m.RESOURCES) == []
