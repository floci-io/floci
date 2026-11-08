"""AppConfig distinguishes a cached version from newly delivered empty content."""

from contextlib import ExitStack

import boto3
import pytest


@pytest.mark.parametrize("by_name", [False, True], ids=["ids", "names"])
@pytest.mark.parametrize(
    "content,content_type",
    [
        (b'{"enabled":true}', "application/json"),
        (b"\x00\xffconfiguration\x80", "application/octet-stream"),
        (b"", "application/octet-stream"),
    ],
    ids=["json", "binary", "empty"],
)
def test_cached_configuration_response(aws_config, client_config, unique_name,
                                       by_name, content, content_type):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        cleanup.callback(client.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted", Type="AWS.Freeform"
        )["Id"]
        cleanup.callback(
            client.delete_configuration_profile,
            ApplicationId=app, ConfigurationProfileId=profile,
        )
        env = client.create_environment(ApplicationId=app, Name="local")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
        version = client.create_hosted_configuration_version(
            ApplicationId=app, ConfigurationProfileId=profile,
            Content=content, ContentType=content_type,
        )["VersionNumber"]
        client.start_deployment(
            ApplicationId=app, EnvironmentId=env, ConfigurationProfileId=profile,
            ConfigurationVersion=str(version), DeploymentStrategyId="AppConfig.AllAtOnce",
        )
        request = {
            "Application": unique_name if by_name else app,
            "Environment": "local" if by_name else env,
            "Configuration": "settings" if by_name else profile,
            "ClientId": unique_name,
        }

        for hint in (None, str(version), "unknown"):
            response = client.get_configuration(
                **request, **({"ClientConfigurationVersion": hint} if hint is not None else {})
            )
            cleanup.callback(response["Content"].close)
            cached = hint == str(version)
            assert response["ResponseMetadata"]["HTTPStatusCode"] == (204 if cached else 200)
            assert response["ConfigurationVersion"] == str(version)
            assert response["Content"].read() == (b"" if cached else content)
            headers = response["ResponseMetadata"]["HTTPHeaders"]
            if cached:
                assert "ContentType" not in response
                assert "content-type" not in headers and "content-length" not in headers
            else:
                assert response["ContentType"] == content_type

        next_version = client.create_hosted_configuration_version(
            ApplicationId=app, ConfigurationProfileId=profile,
            Content=b"changed", ContentType="text/plain",
        )["VersionNumber"]
        client.start_deployment(
            ApplicationId=app, EnvironmentId=env, ConfigurationProfileId=profile,
            ConfigurationVersion=str(next_version), DeploymentStrategyId="AppConfig.AllAtOnce",
        )
        response = client.get_configuration(**request, ClientConfigurationVersion=str(version))
        cleanup.callback(response["Content"].close)
        assert response["ResponseMetadata"]["HTTPStatusCode"] == 200
        assert response["ConfigurationVersion"] == str(next_version)
        assert response["ContentType"] == "text/plain"
        assert response["Content"].read() == b"changed"
