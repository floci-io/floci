"""Secrets Manager integration tests."""

import pytest
from botocore.exceptions import ClientError


class TestSecretsManagerSecret:
    """Test Secrets Manager secret operations."""

    def test_create_secret(self, secretsmanager_client, unique_name):
        """Test CreateSecret creates a secret."""
        secret_name = f"pytest-secret-{unique_name}"
        secret_value = "my-super-secret-value"

        try:
            response = secretsmanager_client.create_secret(
                Name=secret_name,
                SecretString=secret_value,
                Description="Test secret",
                Tags=[{"Key": "env", "Value": "test"}],
            )
            assert response["ARN"]
            assert secret_name in response["ARN"]
            assert response["Name"] == secret_name
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_get_secret_value_by_name(self, secretsmanager_client, unique_name):
        """Test GetSecretValue by name returns secret."""
        secret_name = f"pytest-secret-{unique_name}"
        secret_value = "my-super-secret-value"

        secretsmanager_client.create_secret(Name=secret_name, SecretString=secret_value)
        try:
            response = secretsmanager_client.get_secret_value(SecretId=secret_name)
            assert response["SecretString"] == secret_value
            assert response["Name"] == secret_name
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_get_secret_value_by_arn(self, secretsmanager_client, unique_name):
        """Test GetSecretValue by ARN returns secret."""
        secret_name = f"pytest-secret-{unique_name}"
        secret_value = "my-super-secret-value"

        response = secretsmanager_client.create_secret(
            Name=secret_name, SecretString=secret_value
        )
        secret_arn = response["ARN"]

        try:
            response = secretsmanager_client.get_secret_value(SecretId=secret_arn)
            assert response["SecretString"] == secret_value
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_put_secret_value(self, secretsmanager_client, unique_name):
        """Test PutSecretValue updates secret."""
        secret_name = f"pytest-secret-{unique_name}"

        response = secretsmanager_client.create_secret(
            Name=secret_name, SecretString="original"
        )
        original_version = response["VersionId"]

        try:
            response = secretsmanager_client.put_secret_value(
                SecretId=secret_name, SecretString="updated"
            )
            new_version = response["VersionId"]
            assert new_version
            assert new_version != original_version

            response = secretsmanager_client.get_secret_value(SecretId=secret_name)
            assert response["SecretString"] == "updated"
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_describe_secret(self, secretsmanager_client, unique_name):
        """Test DescribeSecret returns secret metadata."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(
            Name=secret_name,
            SecretString="value",
            Tags=[{"Key": "env", "Value": "test"}],
        )

        try:
            response = secretsmanager_client.describe_secret(SecretId=secret_name)
            assert response.get("Tags")
            assert not response.get("RotationEnabled", True)
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_update_secret(self, secretsmanager_client, unique_name):
        """Test UpdateSecret updates secret description."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(
            Name=secret_name, SecretString="value", Description="Original"
        )

        try:
            secretsmanager_client.update_secret(
                SecretId=secret_name, Description="Updated description"
            )

            response = secretsmanager_client.describe_secret(SecretId=secret_name)
            assert response.get("Description") == "Updated description"
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_list_secrets(self, secretsmanager_client, unique_name):
        """Test ListSecrets includes created secret."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(Name=secret_name, SecretString="value")

        try:
            response = secretsmanager_client.list_secrets()
            assert any(s["Name"] == secret_name for s in response["SecretList"])
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_delete_secret(self, secretsmanager_client, unique_name):
        """Test DeleteSecret removes secret."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(Name=secret_name, SecretString="value")
        secretsmanager_client.delete_secret(
            SecretId=secret_name, ForceDeleteWithoutRecovery=True
        )

        with pytest.raises(ClientError) as exc_info:
            secretsmanager_client.get_secret_value(SecretId=secret_name)
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400


class TestSecretsManagerTags:
    """Test Secrets Manager tagging operations."""

    def test_tag_resource(self, secretsmanager_client, unique_name):
        """Test TagResource adds tags to secret."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(Name=secret_name, SecretString="value")

        try:
            secretsmanager_client.tag_resource(
                SecretId=secret_name, Tags=[{"Key": "team", "Value": "backend"}]
            )

            response = secretsmanager_client.describe_secret(SecretId=secret_name)
            tags = {t["Key"]: t["Value"] for t in response.get("Tags", [])}
            assert tags.get("team") == "backend"
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_untag_resource(self, secretsmanager_client, unique_name):
        """Test UntagResource removes tags from secret."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(
            Name=secret_name,
            SecretString="value",
            Tags=[{"Key": "team", "Value": "backend"}],
        )

        try:
            secretsmanager_client.untag_resource(
                SecretId=secret_name, TagKeys=["team"]
            )

            response = secretsmanager_client.describe_secret(SecretId=secret_name)
            tags = {t["Key"] for t in response.get("Tags", [])}
            assert "team" not in tags
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )


class TestSecretsManagerVersions:
    """Test Secrets Manager version operations."""

    def test_list_secret_version_ids(self, secretsmanager_client, unique_name):
        """Test ListSecretVersionIds returns version stages."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(Name=secret_name, SecretString="v1")
        secretsmanager_client.put_secret_value(SecretId=secret_name, SecretString="v2")

        try:
            response = secretsmanager_client.list_secret_version_ids(
                SecretId=secret_name
            )
            stages = [
                stage for v in response["Versions"] for stage in v.get("VersionStages", [])
            ]
            assert "AWSCURRENT" in stages
            assert "AWSPREVIOUS" in stages
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )


class TestSecretsManagerErrors:
    """Test Secrets Manager error handling."""

    def test_create_secret_duplicate(self, secretsmanager_client, unique_name):
        """Test CreateSecret returns error for duplicate name."""
        secret_name = f"pytest-secret-{unique_name}"

        secretsmanager_client.create_secret(Name=secret_name, SecretString="v1")

        try:
            with pytest.raises(ClientError) as exc_info:
                secretsmanager_client.create_secret(
                    Name=secret_name, SecretString="v2"
                )
            assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        finally:
            secretsmanager_client.delete_secret(
                SecretId=secret_name, ForceDeleteWithoutRecovery=True
            )

    def test_get_secret_value_non_existent(self, secretsmanager_client, unique_name):
        """Test GetSecretValue returns error for non-existent secret."""
        with pytest.raises(ClientError) as exc_info:
            secretsmanager_client.get_secret_value(
                SecretId=f"non-existent-secret-{unique_name}"
            )
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
