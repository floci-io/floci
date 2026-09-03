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


class TestCognitoLogDeliveryConfiguration:
    """Test Cognito log delivery configuration operations.

    ``LogConfigurations`` is always present on the response, as ``[]`` when nothing
    is configured, and ``SetLogDeliveryConfiguration`` replaces the list rather than
    merging into it.
    """

    LOG_GROUP_ARN = "arn:aws:logs:us-east-1:000000000000:log-group:pytest-cognito-logs"

    @pytest.fixture
    def pool_id(self, cognito_client, unique_name):
        response = cognito_client.create_user_pool(PoolName=f"pytest-log-pool-{unique_name}")
        pool_id = response["UserPool"]["Id"]
        yield pool_id
        cognito_client.delete_user_pool(UserPoolId=pool_id)

    def test_get_returns_an_empty_list_before_anything_is_configured(
        self, cognito_client, pool_id
    ):
        """An unconfigured pool still returns the LogConfigurations member."""
        config = cognito_client.get_log_delivery_configuration(UserPoolId=pool_id)[
            "LogDeliveryConfiguration"
        ]

        assert config["UserPoolId"] == pool_id
        assert config["LogConfigurations"] == []

    def test_set_round_trips_and_replaces(self, cognito_client, pool_id):
        """Set stores the configuration, and a second Set replaces rather than merges."""
        cognito_client.set_log_delivery_configuration(
            UserPoolId=pool_id,
            LogConfigurations=[
                {
                    "LogLevel": "ERROR",
                    "EventSource": "userNotification",
                    "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
                }
            ],
        )

        config = cognito_client.get_log_delivery_configuration(UserPoolId=pool_id)[
            "LogDeliveryConfiguration"
        ]
        assert len(config["LogConfigurations"]) == 1
        assert config["LogConfigurations"][0]["EventSource"] == "userNotification"
        assert (
            config["LogConfigurations"][0]["CloudWatchLogsConfiguration"]["LogGroupArn"]
            == self.LOG_GROUP_ARN
        )

        cognito_client.set_log_delivery_configuration(
            UserPoolId=pool_id,
            LogConfigurations=[
                {
                    "LogLevel": "INFO",
                    "EventSource": "userAuthEvents",
                    "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
                }
            ],
        )
        replaced = cognito_client.get_log_delivery_configuration(UserPoolId=pool_id)[
            "LogDeliveryConfiguration"
        ]
        assert len(replaced["LogConfigurations"]) == 1
        assert replaced["LogConfigurations"][0]["EventSource"] == "userAuthEvents"

    def test_empty_list_clears_the_configuration(self, cognito_client, pool_id):
        """An empty LogConfigurations clears what was stored."""
        cognito_client.set_log_delivery_configuration(
            UserPoolId=pool_id,
            LogConfigurations=[
                {
                    "LogLevel": "ERROR",
                    "EventSource": "userNotification",
                    "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
                }
            ],
        )

        cognito_client.set_log_delivery_configuration(UserPoolId=pool_id, LogConfigurations=[])

        config = cognito_client.get_log_delivery_configuration(UserPoolId=pool_id)[
            "LogDeliveryConfiguration"
        ]
        assert config["LogConfigurations"] == []

    def test_set_rejects_a_configuration_with_no_destination(self, cognito_client, pool_id):
        """Every event source in the request must name a destination."""
        with pytest.raises(cognito_client.exceptions.InvalidParameterException):
            cognito_client.set_log_delivery_configuration(
                UserPoolId=pool_id,
                LogConfigurations=[{"LogLevel": "ERROR", "EventSource": "userNotification"}],
            )

    def test_set_rejects_more_than_two_configurations(self, cognito_client, pool_id):
        """LogConfigurations is bounded at 2 entries."""
        config = {
            "LogLevel": "ERROR",
            "EventSource": "userNotification",
            "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
        }
        with pytest.raises(cognito_client.exceptions.InvalidParameterException) as excinfo:
            cognito_client.set_log_delivery_configuration(
                UserPoolId=pool_id, LogConfigurations=[config, config, config]
            )

        assert "Member must have length less than or equal to 2" in str(excinfo.value)

    def test_set_rejects_a_repeated_event_source(self, cognito_client, pool_id):
        """An event source may appear at most once across the configurations."""
        config = {
            "LogLevel": "ERROR",
            "EventSource": "userNotification",
            "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
        }
        with pytest.raises(cognito_client.exceptions.InvalidParameterException) as excinfo:
            cognito_client.set_log_delivery_configuration(
                UserPoolId=pool_id, LogConfigurations=[config, config]
            )

        assert "appear more then once in a request" in str(excinfo.value)

    def test_a_rejected_request_leaves_the_configuration_alone(self, cognito_client, pool_id):
        """An oversized request must not be stored."""
        config = {
            "LogLevel": "ERROR",
            "EventSource": "userNotification",
            "CloudWatchLogsConfiguration": {"LogGroupArn": self.LOG_GROUP_ARN},
        }
        with pytest.raises(cognito_client.exceptions.InvalidParameterException):
            cognito_client.set_log_delivery_configuration(
                UserPoolId=pool_id, LogConfigurations=[config, config, config]
            )

        stored = cognito_client.get_log_delivery_configuration(UserPoolId=pool_id)[
            "LogDeliveryConfiguration"
        ]
        assert stored["LogConfigurations"] == []


class TestCognitoDescribeUserPoolStandardAttributes:
    """DescribeUserPool must return all 20 standard OIDC attributes."""

    STANDARD_ATTRIBUTES = [
        "sub", "name", "given_name", "family_name", "middle_name", "nickname",
        "preferred_username", "profile", "picture", "website", "email",
        "email_verified", "gender", "birthdate", "zoneinfo", "locale",
        "phone_number", "phone_number_verified", "address", "updated_at",
    ]

    def test_describe_user_pool_returns_all_standard_schema_attributes(self, cognito_client, unique_name):
        response = cognito_client.create_user_pool(PoolName=f"pytest-schema-{unique_name}")
        pool_id = response["UserPool"]["Id"]

        try:
            described = cognito_client.describe_user_pool(UserPoolId=pool_id)
            schema = described["UserPool"]["SchemaAttributes"]
            names = [a["Name"] for a in schema]

            assert len(schema) == 20
            for attr in self.STANDARD_ATTRIBUTES:
                assert attr in names, f"Missing standard attribute: {attr}"

            sub = next(a for a in schema if a["Name"] == "sub")
            assert sub["Required"] is True
            assert sub["Mutable"] is False
        finally:
            cognito_client.delete_user_pool(UserPoolId=pool_id)


class TestCognitoIdentityProvider:
    """Test Cognito identity provider configuration operations.

    The response shapes asserted here were measured against the live Cognito API.
    ``IdpIdentifiers`` is echoed by CreateIdentityProvider only when the request
    supplied it, while DescribeIdentityProvider always returns it.
    """

    OIDC_DETAILS = {
        "client_id": "pytest-client",
        "client_secret": "pytest-secret",
        "attributes_request_method": "GET",
        "oidc_issuer": "https://issuer.example.com",
        "authorize_scopes": "openid",
    }

    @pytest.fixture
    def pool_id(self, cognito_client, unique_name):
        response = cognito_client.create_user_pool(PoolName=f"pytest-idp-pool-{unique_name}")
        pool_id = response["UserPool"]["Id"]
        yield pool_id
        cognito_client.delete_user_pool(UserPoolId=pool_id)

    def test_create_defaults_attribute_mapping_and_omits_idp_identifiers(
        self, cognito_client, pool_id
    ):
        """Create defaults AttributeMapping and omits IdpIdentifiers when not supplied."""
        provider = cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )["IdentityProvider"]

        assert provider["ProviderType"] == "OIDC"
        assert provider["AttributeMapping"] == {"username": "sub"}
        assert "IdpIdentifiers" not in provider

    def test_describe_always_returns_idp_identifiers(self, cognito_client, pool_id):
        """Describe returns IdpIdentifiers even when the stored list is empty."""
        cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )

        provider = cognito_client.describe_identity_provider(
            UserPoolId=pool_id, ProviderName="PytestOidc"
        )["IdentityProvider"]

        assert provider["IdpIdentifiers"] == []
        assert provider["ProviderDetails"]["client_id"] == "pytest-client"

    def test_update_preserves_members_the_request_omits(self, cognito_client, pool_id):
        """Omitting AttributeMapping/IdpIdentifiers on update leaves them unchanged."""
        cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
            AttributeMapping={"email": "email", "username": "sub"},
            IdpIdentifiers=["pytest-alias"],
        )

        cognito_client.update_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )

        provider = cognito_client.describe_identity_provider(
            UserPoolId=pool_id, ProviderName="PytestOidc"
        )["IdentityProvider"]

        assert provider["IdpIdentifiers"] == ["pytest-alias"]
        assert provider["AttributeMapping"] == {"email": "email", "username": "sub"}

    def test_list_returns_summaries_without_provider_details(self, cognito_client, pool_id):
        """ListIdentityProviders returns summaries, never provider credentials."""
        cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )

        providers = cognito_client.list_identity_providers(UserPoolId=pool_id)["Providers"]

        assert len(providers) == 1
        assert providers[0]["ProviderName"] == "PytestOidc"
        assert providers[0]["ProviderType"] == "OIDC"
        assert "ProviderDetails" not in providers[0]

    def test_create_rejects_duplicate_provider_name(self, cognito_client, pool_id):
        """A second provider with the same name raises DuplicateProviderException."""
        cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )

        with pytest.raises(cognito_client.exceptions.DuplicateProviderException):
            cognito_client.create_identity_provider(
                UserPoolId=pool_id,
                ProviderName="PytestOidc",
                ProviderType="OIDC",
                ProviderDetails=dict(self.OIDC_DETAILS),
            )

    def test_delete_removes_the_provider(self, cognito_client, pool_id):
        """Delete removes the provider and a second delete raises."""
        cognito_client.create_identity_provider(
            UserPoolId=pool_id,
            ProviderName="PytestOidc",
            ProviderType="OIDC",
            ProviderDetails=dict(self.OIDC_DETAILS),
        )

        cognito_client.delete_identity_provider(UserPoolId=pool_id, ProviderName="PytestOidc")

        assert cognito_client.list_identity_providers(UserPoolId=pool_id)["Providers"] == []
        with pytest.raises(cognito_client.exceptions.ResourceNotFoundException):
            cognito_client.delete_identity_provider(
                UserPoolId=pool_id, ProviderName="PytestOidc"
            )
