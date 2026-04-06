"""Cognito Identity Provider integration tests."""

import pytest


class TestCognitoUserPool:
    """Test Cognito user pool operations."""

    def test_create_user_pool(self, cognito_client, unique_name):
        """Test CreateUserPool creates a pool."""
        pool_name = f"pytest-pool-{unique_name}"

        try:
            response = cognito_client.create_user_pool(PoolName=pool_name)
            pool_id = response["UserPool"]["Id"]
            assert pool_id
        finally:
            cognito_client.delete_user_pool(UserPoolId=response["UserPool"]["Id"])

    def test_delete_user_pool(self, cognito_client, unique_name):
        """Test DeleteUserPool removes pool."""
        pool_name = f"pytest-pool-{unique_name}"

        response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = response["UserPool"]["Id"]

        cognito_client.delete_user_pool(UserPoolId=pool_id)
        # If no exception, test passes


class TestCognitoUserPoolClient:
    """Test Cognito user pool client operations."""

    def test_create_user_pool_client(self, cognito_client, unique_name):
        """Test CreateUserPoolClient creates a client."""
        pool_name = f"pytest-pool-{unique_name}"
        client_name = f"pytest-client-{unique_name}"

        response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = response["UserPool"]["Id"]

        try:
            response = cognito_client.create_user_pool_client(
                UserPoolId=pool_id, ClientName=client_name
            )
            client_id = response["UserPoolClient"]["ClientId"]
            assert client_id
        finally:
            cognito_client.delete_user_pool(UserPoolId=pool_id)

    def test_delete_user_pool_client(self, cognito_client, unique_name):
        """Test DeleteUserPoolClient removes client."""
        pool_name = f"pytest-pool-{unique_name}"
        client_name = f"pytest-client-{unique_name}"

        pool_response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = pool_response["UserPool"]["Id"]

        client_response = cognito_client.create_user_pool_client(
            UserPoolId=pool_id, ClientName=client_name
        )
        client_id = client_response["UserPoolClient"]["ClientId"]

        try:
            cognito_client.delete_user_pool_client(
                UserPoolId=pool_id, ClientId=client_id
            )
            # If no exception, test passes
        finally:
            cognito_client.delete_user_pool(UserPoolId=pool_id)


class TestCognitoUser:
    """Test Cognito user operations."""

    def test_admin_create_user(self, cognito_client, unique_name):
        """Test AdminCreateUser creates a user."""
        pool_name = f"pytest-pool-{unique_name}"
        username = f"pytest-user-{unique_name}"

        pool_response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = pool_response["UserPool"]["Id"]

        try:
            response = cognito_client.admin_create_user(
                UserPoolId=pool_id,
                Username=username,
                UserAttributes=[{"Name": "email", "Value": "pytest@example.com"}],
            )
            assert response["User"]["Username"] == username
        finally:
            cognito_client.admin_delete_user(UserPoolId=pool_id, Username=username)
            cognito_client.delete_user_pool(UserPoolId=pool_id)

    def test_admin_delete_user(self, cognito_client, unique_name):
        """Test AdminDeleteUser removes user."""
        pool_name = f"pytest-pool-{unique_name}"
        username = f"pytest-user-{unique_name}"

        pool_response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = pool_response["UserPool"]["Id"]

        cognito_client.admin_create_user(
            UserPoolId=pool_id,
            Username=username,
            UserAttributes=[{"Name": "email", "Value": "pytest@example.com"}],
        )

        try:
            cognito_client.admin_delete_user(UserPoolId=pool_id, Username=username)
            # If no exception, test passes
        finally:
            cognito_client.delete_user_pool(UserPoolId=pool_id)


class TestCognitoAuth:
    """Test Cognito authentication operations."""

    def test_admin_initiate_auth(self, cognito_client, unique_name):
        """Test AdminInitiateAuth returns tokens."""
        pool_name = f"pytest-pool-{unique_name}"
        client_name = f"pytest-client-{unique_name}"
        username = f"pytest-user-{unique_name}"

        pool_response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = pool_response["UserPool"]["Id"]

        client_response = cognito_client.create_user_pool_client(
            UserPoolId=pool_id, ClientName=client_name
        )
        client_id = client_response["UserPoolClient"]["ClientId"]

        cognito_client.admin_create_user(
            UserPoolId=pool_id,
            Username=username,
            UserAttributes=[{"Name": "email", "Value": "pytest@example.com"}],
        )

        try:
            response = cognito_client.admin_initiate_auth(
                UserPoolId=pool_id,
                ClientId=client_id,
                AuthFlow="ADMIN_NO_SRP_AUTH",
                AuthParameters={"USERNAME": username, "PASSWORD": "any"},
            )
            access_token = response["AuthenticationResult"]["AccessToken"]
            assert access_token
        finally:
            cognito_client.admin_delete_user(UserPoolId=pool_id, Username=username)
            cognito_client.delete_user_pool_client(
                UserPoolId=pool_id, ClientId=client_id
            )
            cognito_client.delete_user_pool(UserPoolId=pool_id)

    def test_get_user(self, cognito_client, unique_name):
        """Test GetUser returns user details from access token."""
        pool_name = f"pytest-pool-{unique_name}"
        client_name = f"pytest-client-{unique_name}"
        username = f"pytest-user-{unique_name}"

        pool_response = cognito_client.create_user_pool(PoolName=pool_name)
        pool_id = pool_response["UserPool"]["Id"]

        client_response = cognito_client.create_user_pool_client(
            UserPoolId=pool_id, ClientName=client_name
        )
        client_id = client_response["UserPoolClient"]["ClientId"]

        cognito_client.admin_create_user(
            UserPoolId=pool_id,
            Username=username,
            UserAttributes=[{"Name": "email", "Value": "pytest@example.com"}],
        )

        auth_response = cognito_client.admin_initiate_auth(
            UserPoolId=pool_id,
            ClientId=client_id,
            AuthFlow="ADMIN_NO_SRP_AUTH",
            AuthParameters={"USERNAME": username, "PASSWORD": "any"},
        )
        access_token = auth_response["AuthenticationResult"]["AccessToken"]

        try:
            response = cognito_client.get_user(AccessToken=access_token)
            assert response["Username"] == username
        finally:
            cognito_client.admin_delete_user(UserPoolId=pool_id, Username=username)
            cognito_client.delete_user_pool_client(
                UserPoolId=pool_id, ClientId=client_id
            )
            cognito_client.delete_user_pool(UserPoolId=pool_id)
