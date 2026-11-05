"""AppConfig names, deployed content and session tokens belong to their request scope."""

from contextlib import ExitStack
import json

import boto3
import pytest
from botocore.exceptions import ClientError


@pytest.mark.parametrize("regions", [
    ("us-east-1", "us-west-2"),
    ("eu-west-1", "us-west-2"),
    ("cn-north-1", "cn-northwest-1"),
])
def test_same_names_in_other_accounts_and_regions_do_not_alias(
        aws_config, client_config, unique_name, regions):
    with ExitStack() as cleanup:
        selections = []
        for account, region in [
                ("111111111111", regions[0]),
                ("111111111111", regions[1]),
                ("222222222222", regions[0])]:
            config = {
                **aws_config, "region_name": region,
                "aws_access_key_id": account, "aws_secret_access_key": "test",
            }
            client = boto3.client("appconfig", config=client_config, **config)
            data = boto3.client("appconfigdata", config=client_config, **config)
            cleanup.callback(client.close)
            cleanup.callback(data.close)
            app = client.create_application(Name=unique_name)["Id"]
            cleanup.callback(client.delete_application, ApplicationId=app)
            profile = client.create_configuration_profile(
                ApplicationId=app, Name="settings", LocationUri="hosted", Type="AWS.Freeform")["Id"]
            cleanup.callback(
                client.delete_configuration_profile, ApplicationId=app, ConfigurationProfileId=profile)
            content = json.dumps({"account": account, "region": region}).encode()
            version = client.create_hosted_configuration_version(
                ApplicationId=app, ConfigurationProfileId=profile, Content=content,
                ContentType="application/json")["VersionNumber"]
            cleanup.callback(
                client.delete_hosted_configuration_version, ApplicationId=app,
                ConfigurationProfileId=profile, VersionNumber=version)
            strategy = client.create_deployment_strategy(
                Name=unique_name, DeploymentDurationInMinutes=0, GrowthFactor=100,
                FinalBakeTimeInMinutes=0, ReplicateTo="NONE")["Id"]
            cleanup.callback(client.delete_deployment_strategy, DeploymentStrategyId=strategy)
            env = client.create_environment(ApplicationId=app, Name="environment")["Id"]
            cleanup.callback(client.delete_environment, ApplicationId=app, EnvironmentId=env)
            client.start_deployment(
                ApplicationId=app, EnvironmentId=env, ConfigurationProfileId=profile,
                ConfigurationVersion=str(version), DeploymentStrategyId=strategy)
            token = data.start_configuration_session(
                ApplicationIdentifier=app, EnvironmentIdentifier=env,
                ConfigurationProfileIdentifier=profile)["InitialConfigurationToken"]
            selections.append({
                "client": client, "data": data, "app": app, "strategy": strategy,
                "content": content, "version": version, "token": token,
            })

        for selection in selections:
            client = selection["client"]
            data = selection["data"]
            assert client.get_application(ApplicationId=unique_name)["Id"] == selection["app"]
            assert [
                app["Id"] for app in client.list_applications()["Items"]
                if app["Name"] == unique_name
            ] == [selection["app"]]
            response = client.get_configuration(
                Application=unique_name, Environment="environment", Configuration="settings",
                ClientId=unique_name)
            with response["Content"] as stream:
                assert stream.read() == selection["content"]
            assert response["ConfigurationVersion"] == str(selection["version"])
            for other in selections:
                if other is selection:
                    continue
                with pytest.raises(ClientError) as error:
                    client.get_application(ApplicationId=other["app"])
                assert error.value.response["Error"]["Code"] == "ResourceNotFoundException"
                with pytest.raises(ClientError) as error:
                    client.get_deployment_strategy(DeploymentStrategyId=other["strategy"])
                assert error.value.response["Error"]["Code"] == "ResourceNotFoundException"
                with pytest.raises(ClientError) as error:
                    data.get_latest_configuration(ConfigurationToken=other["token"])
                assert error.value.response["Error"]["Code"] == "BadRequestException"
            response = data.get_latest_configuration(ConfigurationToken=selection["token"])
            with response["Configuration"] as stream:
                assert stream.read() == selection["content"]
