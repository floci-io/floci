"""Name ambiguity must not choose another application's configuration."""

from contextlib import ExitStack

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("resource", ["application", "environment", "profile"])
def test_ambiguous_names_are_refused_and_exact_ids_still_deliver(
        aws_config, client_config, unique_name, resource):
    with ExitStack() as cleanup:
        client = boto3.client("appconfig", config=client_config, **aws_config)
        data = boto3.client("appconfigdata", config=client_config, **aws_config)
        cleanup.callback(client.close)
        cleanup.callback(data.close)
        app = client.create_application(Name=unique_name)["Id"]
        cleanup.callback(client.delete_application, ApplicationId=app)
        profile = client.create_configuration_profile(
            ApplicationId=app, Name="settings", LocationUri="hosted", Type="AWS.Freeform"
        )["Id"]
        cleanup.callback(
            client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
        content = b'{"scope":"owned"}'
        version = client.create_hosted_configuration_version(
            ApplicationId=app, ConfigurationProfileId=profile,
            Content=content, ContentType="application/json",
        )["VersionNumber"]
        cleanup.callback(
            client.delete_hosted_configuration_version, ApplicationId=app,
            ConfigurationProfileId=profile, VersionNumber=version)
        env = client.create_environment(ApplicationId=app, Name="local")["Id"]
        cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
        client.start_deployment(
            ApplicationId=app, EnvironmentId=env, ConfigurationProfileId=profile,
            ConfigurationVersion=str(version), DeploymentStrategyId="AppConfig.AllAtOnce")

        def duplicate(name):
            if resource == "application":
                added = client.create_application(Name=name)["Id"]
                cleanup.callback(client.delete_application, ApplicationId=added)
            elif resource == "environment":
                added = client.create_environment(ApplicationId=app, Name=name)["Id"]
                cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=added)
            else:
                added = client.create_configuration_profile(
                    ApplicationId=app, Name=name, LocationUri="hosted", Type="AWS.Freeform")["Id"]
                cleanup.callback(
                    client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=added)

        names = {"application": unique_name, "environment": "local", "profile": "settings"}
        ids = {"application": app, "environment": env, "profile": profile}
        duplicate(names[resource])
        duplicate(ids[resource])
        selected = dict(ids)
        selected[resource] = names[resource]
        with pytest.raises(ClientError) as error:
            client.get_configuration(
                Application=selected["application"], Environment=selected["environment"],
                Configuration=selected["profile"], ClientId=unique_name)
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400
        with pytest.raises(ClientError) as error:
            data.start_configuration_session(
                ApplicationIdentifier=selected["application"],
                EnvironmentIdentifier=selected["environment"],
                ConfigurationProfileIdentifier=selected["profile"])
        assert error.value.response["Error"]["Code"] == "BadRequestException"
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 400

        response = client.get_configuration(
            Application=app, Environment=env, Configuration=profile, ClientId=unique_name)
        with response["Content"] as stream:
            assert stream.read() == content
        assert response["ConfigurationVersion"] == str(version)
        token = data.start_configuration_session(
            ApplicationIdentifier=app, EnvironmentIdentifier=env,
            ConfigurationProfileIdentifier=profile)["InitialConfigurationToken"]
        response = data.get_latest_configuration(ConfigurationToken=token)
        with response["Configuration"] as stream:
            assert stream.read() == content
