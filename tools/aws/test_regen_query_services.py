"""Tests for regen_query_services.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import regen_query_services as r


def model(api_version: str, *operations: str) -> dict:
    return {"metadata": {"apiVersion": api_version}, "operations": {name: {} for name in operations}}


def test_a_service_gets_every_operation_and_version_of_its_models():
    doc = r.build({
        "rds": [model("2014-10-31", "CreateDBCluster", "DescribeEvents"),
                model("2014-10-31", "CreateDBCluster", "CreateGlobalCluster")],
        "elasticloadbalancing": [model("2015-12-01", "CreateLoadBalancer", "CreateTargetGroup"),
                                 model("2012-06-01", "CreateLoadBalancer", "ConfigureHealthCheck")],
    }, "botocore test")

    assert doc["_source"] == {"generator": "tools/aws/regen_query_services.py", "botocore": "botocore test"}
    assert doc["services"] == {
        "elasticloadbalancing": {
            "apiVersions": ["2012-06-01", "2015-12-01"],
            "operations": ["ConfigureHealthCheck", "CreateLoadBalancer", "CreateTargetGroup"],
        },
        "rds": {
            "apiVersions": ["2014-10-31"],
            "operations": ["CreateDBCluster", "CreateGlobalCluster", "DescribeEvents"],
        },
    }


def test_the_latest_api_version_is_read(tmp_path):
    for version in ("2014-09-01", "2016-11-15", "2015-10-01"):
        (tmp_path / "ec2" / version).mkdir(parents=True)

    assert r.latest_model_dir(tmp_path, "ec2").name == "2016-11-15"


def test_check_ignores_the_provenance_and_catches_a_changed_operation(tmp_path):
    data = tmp_path / "data"
    for name, models in r.SERVICES.items():
        for index, botocore_model in enumerate(models):
            directory = data / botocore_model / "2020-01-01"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "service-2.json").write_text(
                json.dumps(model("2020-01-01", f"{name.title()}{index}")), encoding="utf-8")
    output = tmp_path / "query-services.json"

    assert r.main(["--botocore-data", str(data), "--output", str(output)]) == 0
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 0

    stale = json.loads(output.read_text(encoding="utf-8"))
    stale["_source"]["botocore"] = "another label"
    output.write_text(json.dumps(stale), encoding="utf-8")
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 0

    stale["services"]["sqs"]["operations"].append("Unlisted")
    output.write_text(json.dumps(stale), encoding="utf-8")
    assert r.main(["--check", "--botocore-data", str(data), "--output", str(output)]) == 1
