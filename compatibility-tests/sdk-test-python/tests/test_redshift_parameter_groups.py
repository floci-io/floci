"""Redshift parameter group compatibility with boto3's Query list encoding."""

from contextlib import closing

import boto3
import pytest
from botocore.exceptions import ClientError


def test_reset_all_accepts_empty_parameter_list(aws_config, client_config, unique_name):
    name = f"pytest-redshift-pg-{unique_name}"
    with closing(boto3.client("redshift", config=client_config, **aws_config)) as client:
        client.create_cluster_parameter_group(
            ParameterGroupName=name,
            ParameterGroupFamily="redshift-1.0",
            Description="Empty Query list regression",
        )
        try:
            initial = client.describe_cluster_parameters(ParameterGroupName=name)["Parameters"]
            default_value = next(
                parameter["ParameterValue"]
                for parameter in initial
                if parameter["ParameterName"] == "max_cursor_result_set_size"
            )
            client.modify_cluster_parameter_group(
                ParameterGroupName=name,
                Parameters=[
                    {"ParameterName": "max_cursor_result_set_size", "ParameterValue": "1000"}
                ],
            )

            with pytest.raises(ClientError) as failure:
                client.reset_cluster_parameter_group(
                    ParameterGroupName=name, ResetAllParameters=False, Parameters=[]
                )
            assert failure.value.response["Error"]["Code"] == "InvalidParameterCombination"
            unchanged = client.describe_cluster_parameters(
                ParameterGroupName=name, Source="user"
            )["Parameters"]
            assert unchanged[0]["ParameterValue"] == "1000"

            response = client.reset_cluster_parameter_group(
                ParameterGroupName=name, ResetAllParameters=True, Parameters=[]
            )

            assert response["ParameterGroupName"] == name
            restored = client.describe_cluster_parameters(ParameterGroupName=name)["Parameters"]
            parameter = next(
                parameter
                for parameter in restored
                if parameter["ParameterName"] == "max_cursor_result_set_size"
            )
            assert parameter["ParameterValue"] == default_value
            assert parameter["Source"] == "engine-default"
        finally:
            client.delete_cluster_parameter_group(ParameterGroupName=name)
