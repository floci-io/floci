"""SSM Parameter Store integration tests."""

import pytest
from botocore.exceptions import ClientError


class TestSSMParameter:
    """Test SSM Parameter Store operations."""

    def test_put_parameter(self, ssm_client, unique_name):
        """Test PutParameter creates a parameter with version > 0."""
        param_name = f"/pytest-sdk-test/{unique_name}"
        param_value = "param-value-boto3"

        try:
            response = ssm_client.put_parameter(
                Name=param_name, Value=param_value, Type="String", Overwrite=True
            )
            assert response["Version"] > 0
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_get_parameter(self, ssm_client, unique_name):
        """Test GetParameter retrieves correct value."""
        param_name = f"/pytest-sdk-test/{unique_name}"
        param_value = "param-value-boto3"

        ssm_client.put_parameter(Name=param_name, Value=param_value, Type="String")
        try:
            response = ssm_client.get_parameter(Name=param_name, WithDecryption=False)
            assert response["Parameter"]["Value"] == param_value
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_label_parameter_version(self, ssm_client, unique_name):
        """Test LabelParameterVersion adds label to parameter."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        try:
            ssm_client.label_parameter_version(
                Name=param_name, Labels=["py-label"], ParameterVersion=1
            )
            # If no exception, test passes
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_get_parameter_history(self, ssm_client, unique_name):
        """Test GetParameterHistory returns parameter versions."""
        param_name = f"/pytest-sdk-test/{unique_name}"
        param_value = "param-value-boto3"

        ssm_client.put_parameter(Name=param_name, Value=param_value, Type="String")
        try:
            response = ssm_client.get_parameter_history(
                Name=param_name, WithDecryption=False
            )
            found = any(p["Value"] == param_value for p in response["Parameters"])
            assert found
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_get_parameters(self, ssm_client, unique_name):
        """Test GetParameters retrieves multiple parameters."""
        param_name = f"/pytest-sdk-test/{unique_name}"
        param_value = "param-value-boto3"

        ssm_client.put_parameter(Name=param_name, Value=param_value, Type="String")
        try:
            response = ssm_client.get_parameters(Names=[param_name])
            found = any(
                p["Name"] == param_name and p["Value"] == param_value
                for p in response["Parameters"]
            )
            assert found
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_describe_parameters(self, ssm_client, unique_name):
        """Test DescribeParameters lists parameters."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        try:
            response = ssm_client.describe_parameters()
            found = any(p["Name"] == param_name for p in response["Parameters"])
            assert found
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_get_parameters_by_path(self, ssm_client, unique_name):
        """Test GetParametersByPath retrieves parameters under a path."""
        path = f"/pytest-sdk-test/{unique_name}"
        param_name = f"{path}/param"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        try:
            response = ssm_client.get_parameters_by_path(Path=path, Recursive=True)
            found = any(p["Name"] == param_name for p in response["Parameters"])
            assert found
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_add_tags_to_resource(self, ssm_client, unique_name):
        """Test AddTagsToResource adds tags to parameter."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        try:
            ssm_client.add_tags_to_resource(
                ResourceType="Parameter",
                ResourceId=param_name,
                Tags=[
                    {"Key": "env", "Value": "test"},
                    {"Key": "team", "Value": "backend"},
                ],
            )
            # If no exception, test passes
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_list_tags_for_resource(self, ssm_client, unique_name):
        """Test ListTagsForResource returns tags."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        ssm_client.add_tags_to_resource(
            ResourceType="Parameter",
            ResourceId=param_name,
            Tags=[
                {"Key": "env", "Value": "test"},
                {"Key": "team", "Value": "backend"},
            ],
        )
        try:
            response = ssm_client.list_tags_for_resource(
                ResourceType="Parameter", ResourceId=param_name
            )
            tags = {t["Key"]: t["Value"] for t in response["TagList"]}
            assert tags.get("env") == "test"
            assert tags.get("team") == "backend"
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_remove_tags_from_resource(self, ssm_client, unique_name):
        """Test RemoveTagsFromResource removes tags."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        ssm_client.add_tags_to_resource(
            ResourceType="Parameter",
            ResourceId=param_name,
            Tags=[
                {"Key": "env", "Value": "test"},
                {"Key": "team", "Value": "backend"},
            ],
        )
        try:
            ssm_client.remove_tags_from_resource(
                ResourceType="Parameter", ResourceId=param_name, TagKeys=["team"]
            )
            response = ssm_client.list_tags_for_resource(
                ResourceType="Parameter", ResourceId=param_name
            )
            tags = {t["Key"]: t["Value"] for t in response["TagList"]}
            assert tags.get("env") == "test"
            assert "team" not in tags
        finally:
            ssm_client.delete_parameter(Name=param_name)

    def test_delete_parameter(self, ssm_client, unique_name):
        """Test DeleteParameter removes parameter."""
        param_name = f"/pytest-sdk-test/{unique_name}"

        ssm_client.put_parameter(Name=param_name, Value="value", Type="String")
        ssm_client.delete_parameter(Name=param_name)

        with pytest.raises(ClientError) as exc_info:
            ssm_client.get_parameter(Name=param_name, WithDecryption=False)
        assert exc_info.value.response["Error"]["Code"] == "ParameterNotFound"

    def test_delete_parameters(self, ssm_client, unique_name):
        """Test DeleteParameters removes multiple parameters."""
        p1 = f"/pytest-sdk-test/{unique_name}/p1"
        p2 = f"/pytest-sdk-test/{unique_name}/p2"

        ssm_client.put_parameter(Name=p1, Value="v1", Type="String")
        ssm_client.put_parameter(Name=p2, Value="v2", Type="String")

        response = ssm_client.delete_parameters(Names=[p1, p2])
        assert len(response["DeletedParameters"]) == 2
