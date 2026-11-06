"""Provisioned concurrency lists use the AWS SDK's unqualified, paginated request."""

import io
import time
import zipfile

import pytest
from botocore.exceptions import ClientError


@pytest.fixture
def provisioned_list_function(lambda_client, minimal_lambda_zip, unique_name):
    created = lambda_client.create_function(
        FunctionName=unique_name, Runtime="nodejs22.x", Handler="index.handler",
        Role="arn:aws:iam::000000000000:role/lambda-role",
        Code={"ZipFile": minimal_lambda_zip},
    )
    try:
        yield unique_name, created["FunctionArn"]
    finally:
        lambda_client.delete_function(FunctionName=unique_name)


@pytest.mark.parametrize("form", ["name", "arn", "partial"])
def test_empty_list_uses_the_unqualified_function(lambda_client, provisioned_list_function, form):
    name, arn = provisioned_list_function
    reference = {"name": name, "arn": arn, "partial": arn.split(":", 4)[4]}[form]
    response = lambda_client.list_provisioned_concurrency_configs(FunctionName=reference)
    assert response["ProvisionedConcurrencyConfigs"] == []
    assert "NextMarker" not in response


def test_list_missing_function_returns_resource_not_found(lambda_client, unique_name):
    with pytest.raises(ClientError) as error:
        lambda_client.list_provisioned_concurrency_configs(FunctionName=unique_name)
    assert error.value.response["Error"]["Code"] == "ResourceNotFoundException"
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


def test_list_preserves_qualifiers_status_pagination_and_deletion(lambda_client, provisioned_list_function):
    name, arn = provisioned_list_function
    first = lambda_client.publish_version(FunctionName=name)["Version"]
    lambda_client.create_alias(FunctionName=name, Name="live", FunctionVersion=first)
    code = io.BytesIO()
    with zipfile.ZipFile(code, "w") as archive:
        archive.writestr("index.js", "exports.handler = async () => 'second version';")
    second = lambda_client.update_function_code(
        FunctionName=name, ZipFile=code.getvalue(), Publish=True
    )["Version"]
    assert second != first
    for qualifier in ("live", second):
        lambda_client.put_provisioned_concurrency_config(
            FunctionName=name, Qualifier=qualifier, ProvisionedConcurrentExecutions=1
        )
    deadline = time.monotonic() + 120
    while True:
        statuses = {
            qualifier: lambda_client.get_provisioned_concurrency_config(
                FunctionName=name, Qualifier=qualifier
            )
            for qualifier in ("live", second)
        }
        assert all(value["Status"] != "FAILED" for value in statuses.values()), statuses
        if all(value["Status"] == "READY" for value in statuses.values()):
            break
        assert time.monotonic() < deadline, statuses
        time.sleep(0.2)
    pages = list(lambda_client.get_paginator("list_provisioned_concurrency_configs").paginate(
        FunctionName=name, PaginationConfig={"PageSize": 1}
    ))
    assert len(pages) == 2
    assert "NextMarker" in pages[0] and "NextMarker" not in pages[1]
    entries = [entry for page in pages for entry in page["ProvisionedConcurrencyConfigs"]]
    assert {entry["FunctionArn"] for entry in entries} == {arn + ":live", arn + ":" + second}
    for entry in entries:
        qualifier = entry["FunctionArn"].rsplit(":", 1)[1]
        for field in ("Status", "RequestedProvisionedConcurrentExecutions",
                      "AllocatedProvisionedConcurrentExecutions", "AvailableProvisionedConcurrentExecutions",
                      "LastModified"):
            assert entry[field] == statuses[qualifier][field]
        assert entry["Status"] == "READY"
        assert entry["AllocatedProvisionedConcurrentExecutions"] == 1
        assert entry["AvailableProvisionedConcurrentExecutions"] == 1
    lambda_client.delete_provisioned_concurrency_config(FunctionName=name, Qualifier="live")
    remaining = lambda_client.list_provisioned_concurrency_configs(FunctionName=name)["ProvisionedConcurrencyConfigs"]
    assert [entry["FunctionArn"] for entry in remaining] == [arn + ":" + second]
