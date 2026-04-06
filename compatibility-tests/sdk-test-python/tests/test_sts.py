"""STS integration tests."""

import pytest
from botocore.exceptions import ClientError, ParamValidationError


class TestSTSIdentity:
    """Test STS identity operations."""

    def test_get_caller_identity(self, sts_client):
        """Test GetCallerIdentity returns identity details."""
        response = sts_client.get_caller_identity()
        assert response.get("Account")
        assert response.get("Arn")
        assert response.get("UserId")

    def test_get_caller_identity_account_id(self, sts_client):
        """Test GetCallerIdentity returns expected account ID."""
        response = sts_client.get_caller_identity()
        assert response["Account"] == "000000000000"


class TestSTSAssumeRole:
    """Test STS assume role operations."""

    def test_assume_role(self, sts_client):
        """Test AssumeRole returns temporary credentials."""
        response = sts_client.assume_role(
            RoleArn="arn:aws:iam::000000000000:role/pytest-assumed-role",
            RoleSessionName="pytest-session",
            DurationSeconds=3600,
        )
        creds = response["Credentials"]
        assert creds["AccessKeyId"].startswith("ASIA")
        assert creds["SecretAccessKey"]
        assert creds["SessionToken"]

    def test_assume_role_assumed_role_user(self, sts_client):
        """Test AssumeRole returns AssumedRoleUser with correct ARN."""
        response = sts_client.assume_role(
            RoleArn="arn:aws:iam::000000000000:role/my-role",
            RoleSessionName="my-session",
        )
        assert "assumed-role/my-role/my-session" in response["AssumedRoleUser"]["Arn"]

    def test_assume_role_with_web_identity(self, sts_client):
        """Test AssumeRoleWithWebIdentity returns credentials."""
        response = sts_client.assume_role_with_web_identity(
            RoleArn="arn:aws:iam::000000000000:role/web-identity-role",
            RoleSessionName="web-session",
            WebIdentityToken="dummy-token",
            DurationSeconds=3600,
        )
        creds = response["Credentials"]
        assert creds["AccessKeyId"].startswith("ASIA")
        assert (
            "assumed-role/web-identity-role/web-session"
            in response["AssumedRoleUser"]["Arn"]
        )

    def test_assume_role_missing_role_arn(self, sts_client):
        """Test AssumeRole validates required RoleArn parameter."""
        with pytest.raises((ClientError, ParamValidationError)):
            sts_client.assume_role(RoleSessionName="s")


class TestSTSSessionToken:
    """Test STS session token operations."""

    def test_get_session_token(self, sts_client):
        """Test GetSessionToken returns temporary credentials."""
        response = sts_client.get_session_token(DurationSeconds=7200)
        creds = response["Credentials"]
        assert creds["AccessKeyId"].startswith("ASIA")
        assert creds["SessionToken"]


class TestSTSFederation:
    """Test STS federation operations."""

    def test_get_federation_token(self, sts_client):
        """Test GetFederationToken returns credentials with federated user."""
        response = sts_client.get_federation_token(
            Name="pytest-fed-user", DurationSeconds=3600
        )
        creds = response["Credentials"]
        assert creds["AccessKeyId"].startswith("ASIA")
        assert "federated-user/pytest-fed-user" in response["FederatedUser"]["Arn"]


class TestSTSUtilities:
    """Test STS utility operations."""

    def test_decode_authorization_message(self, sts_client):
        """Test DecodeAuthorizationMessage decodes message."""
        response = sts_client.decode_authorization_message(
            EncodedMessage="test-encoded-message"
        )
        assert response.get("DecodedMessage")
