"""Lambda Web Functions integration tests."""

import io
import json
import uuid
import zipfile

import boto3
import pytest
from botocore.config import Config

FUNCTION_NAME = "pytest-web-fn"
LAMBDA_TRUST = json.dumps({
    "Version": "2012-10-17",
    "Statement": [{
        "Effect": "Allow",
        "Principal": {"Service": "lambda.amazonaws.com"},
        "Action": "sts:AssumeRole",
    }],
})


class TestLambdaWeb:
    """One function through its life. The tests run in order and share it."""

    @pytest.fixture(scope="class")
    @staticmethod
    def web(aws_config, client_config):
        """Create the role and the code bucket, and remove them with the function."""
        lambda_web = boto3.client("lambda-web", config=client_config, **aws_config)
        s3 = boto3.client("s3", config=client_config, **aws_config)
        iam = boto3.client("iam", config=client_config, **aws_config)
        suffix = uuid.uuid4().hex[:8]
        role_name = f"pytest-web-role-{suffix}"
        bucket = f"pytest-web-code-{suffix}"
        region = aws_config["region_name"]
        # us-east-1 is the only region that takes a bucket without a location constraint.
        location = {} if region == "us-east-1" else {"CreateBucketConfiguration": {"LocationConstraint": region}}
        role = iam.create_role(RoleName=role_name, AssumeRolePolicyDocument=LAMBDA_TRUST)["Role"]["Arn"]
        try:
            s3.create_bucket(Bucket=bucket, **location)
            try:
                # AWS takes code only from a versioned bucket that lets Lambda read it.
                s3.put_bucket_versioning(Bucket=bucket, VersioningConfiguration={"Status": "Enabled"})
                s3.put_bucket_policy(Bucket=bucket, Policy=json.dumps({
                    "Version": "2012-10-17",
                    "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"Service": "lambda.amazonaws.com"},
                        "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                        "Resource": f"arn:aws:s3:::{bucket}/*",
                    }],
                }))
                s3.put_object(Bucket=bucket, Key="app.zip", Body=_app_zip())
                yield {
                    "client": lambda_web,
                    # botocore refuses an out-of-range value before sending it.
                    "unvalidated": boto3.client(
                        "lambda-web", config=client_config.merge(Config(parameter_validation=False)), **aws_config),
                    "region": region,
                    "role_name": role_name,
                    "role": role,
                    "bucket": bucket,
                }
                try:
                    lambda_web.delete_web_function(functionName=FUNCTION_NAME)
                except lambda_web.exceptions.ResourceNotFoundException:
                    pass  # The last test deletes the function itself.
            finally:
                for version in s3.list_object_versions(Bucket=bucket).get("Versions", []):
                    s3.delete_object(Bucket=bucket, Key=version["Key"], VersionId=version["VersionId"])
                s3.delete_bucket(Bucket=bucket)
        finally:
            iam.delete_role(RoleName=role_name)

    @staticmethod
    def build_config(web):
        return {
            "codeConfig": {"s3Object": {"bucket": web["bucket"], "key": "app.zip"}},
            "runtimeConfig": {"runtime": "nodejs24.x"},
        }

    def test_create_web_function_reports_pending_children(self, web):
        client = web["client"]
        created = client.create_web_function(
            functionName=FUNCTION_NAME,
            revisionConfig={
                "buildConfig": self.build_config(web),
                "serviceConfig": {"executionRoleArn": web["role"]},
            },
            endpointConfig={
                "endpointName": "dev",
                "endpointType": "HomeRegion",
                "authType": "ApplicationManaged",
                "autoDeploymentMode": "LatestRevision",
            },
            tags={"env": "sdk"},
        )
        web["function_arn"] = created["functionArn"]
        web["first_revision"] = created["revision"]["revisionId"]
        assert created["functionArn"].endswith(f":web-function/{FUNCTION_NAME}")
        assert created["state"] == "Active"
        assert created["revision"]["state"] == "Pending"
        assert created["endpoint"]["state"] == "Pending"
        assert ".lambda-web." in created["endpoint"]["domainName"]
        assert created["createdAt"]

        client.get_waiter("web_function_endpoint_active").wait(functionName=FUNCTION_NAME, endpointName="dev")
        endpoint = client.get_web_function_endpoint(functionName=FUNCTION_NAME, endpointName="dev")
        assert endpoint["state"] == "Active"
        assert [w["revisionId"] for w in endpoint["revisionWeights"]] == [web["first_revision"]]
        assert web["region"] in endpoint["regionalEndpoints"]
        assert "updateStatus" not in endpoint

    def test_functions_are_addressable_by_name_and_arn(self, web):
        client = web["client"]
        arn = web["function_arn"]
        assert client.get_web_function(functionName=arn)["functionName"] == FUNCTION_NAME
        assert FUNCTION_NAME in [f["functionName"] for f in client.list_web_functions()["functions"]]
        assert client.get_web_account_settings()["accountQuotas"]["maxEndpointsPerFunction"] == 10

        other_region = "us-west-2" if web["region"] == "eu-west-1" else "eu-west-1"
        foreign_arn = arn.replace(f":{web['region']}:", f":{other_region}:")
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.get_web_function(functionName=foreign_arn)
        assert f"The operation is only allowed in the function's home region {other_region}." in _message(error)
        with pytest.raises(client.exceptions.ResourceNotFoundException) as error:
            client.get_web_function(functionName="pytest-no-such-web-fn")
        assert "does not exist." in _message(error)

    def test_a_new_revision_moves_a_latest_revision_endpoint(self, web):
        client = web["client"]
        revision = client.create_web_function_revision(
            functionName=FUNCTION_NAME,
            description="second",
            buildConfig=self.build_config(web),
            serviceConfig={
                "executionRoleArn": web["role"],
                "timeoutSeconds": 60,
                "environmentVariables": {"APP_VERSION": "2"},
            },
        )
        web["second_revision"] = revision["revisionId"]
        assert revision["state"] == "Pending"
        assert revision["serviceConfig"]["timeoutSeconds"] == 60
        assert revision["serviceConfig"]["maxConcurrencyPerEnvironment"] == 64
        assert revision["serviceConfig"]["telemetryConfig"]["loggingConfig"]["logGroup"] == f"/aws/lambda/web/{FUNCTION_NAME}"

        client.get_waiter("web_function_revision_active").wait(
            functionName=FUNCTION_NAME, revisionId=web["second_revision"])
        revisions = client.list_web_function_revisions(functionName=FUNCTION_NAME)["revisions"]
        assert [r["revisionId"] for r in revisions] == [web["second_revision"], web["first_revision"]]
        endpoint = client.get_web_function_endpoint(functionName=FUNCTION_NAME, endpointName="dev")
        assert [w["revisionId"] for w in endpoint["revisionWeights"]] == [web["second_revision"]]

    def test_weighted_routing_and_the_in_use_guard(self, web):
        client = web["client"]
        update = client.update_web_function_endpoint(
            functionName=FUNCTION_NAME,
            endpointName="dev",
            autoDeploymentMode="Disabled",
            revisionWeights=[
                {"revisionId": web["first_revision"], "weight": 90},
                {"revisionId": web["second_revision"], "weight": 10},
            ],
        )
        assert update["updateStatus"] == "InProgress"
        assert len(update["revisionWeights"]) == 2

        client.get_waiter("web_function_endpoint_updated").wait(functionName=FUNCTION_NAME, endpointName="dev")
        endpoint = client.get_web_function_endpoint(functionName=FUNCTION_NAME, endpointName="dev")
        assert endpoint["updateStatus"] == "Successful"

        with pytest.raises(client.exceptions.ConflictException) as error:
            client.delete_web_function_revision(functionName=FUNCTION_NAME, revisionId=web["first_revision"])
        assert "the endpoints [dev] are using it" in _message(error)
        with pytest.raises(client.exceptions.ResourceNotFoundException) as error:
            client.update_web_function_endpoint(
                functionName=FUNCTION_NAME,
                endpointName="dev",
                revisionWeights=[{"revisionId": "rev-zzzzz", "weight": 100}],
            )
        assert "The revision rev-zzzzz does not exist." in _message(error)

    def test_validation_errors_use_the_aws_wording(self, web):
        client = web["client"]
        with pytest.raises(client.exceptions.ValidationException) as error:
            web["unvalidated"].create_web_function_revision(
                functionName=FUNCTION_NAME,
                buildConfig=self.build_config(web),
                serviceConfig={"executionRoleArn": web["role"], "timeoutSeconds": 2},
            )
        assert ("1 validation error detected: Value '2' at 'serviceConfig.timeoutSeconds' failed to satisfy"
                " constraint: Member must have value greater than or equal to 3") in _message(error)
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.get_web_function_revision(functionName=FUNCTION_NAME, revisionId="nope")
        assert "Invalid revision id or ARN 'nope'" in _message(error)
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.create_web_function_endpoint(
                functionName=FUNCTION_NAME,
                endpointName="prod",
                endpointType="HomeRegion",
                authType="ApplicationManaged",
            )
        assert "The revisionWeights must be specified when the autoDeploymentMode is Disabled." in _message(error)

        missing_role = web["role"].replace(web["role_name"], web["role_name"] + "-missing")
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.create_web_function_revision(
                functionName=FUNCTION_NAME,
                buildConfig=self.build_config(web),
                serviceConfig={"executionRoleArn": missing_role},
            )
        assert (f"The execution role {missing_role} cannot be assumed by lambda.amazonaws.com."
                " Verify its trust policy. If the role was just created, retry in a few seconds.") in _message(error)
        with pytest.raises(client.exceptions.AccessDeniedException) as error:
            client.create_web_function_revision(
                functionName=FUNCTION_NAME,
                buildConfig=self.build_config(web),
                serviceConfig={"executionRoleArn": f"arn:aws:iam::111122223333:role/{web['role_name']}"},
            )
        assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 403
        assert "Cross-account pass role is not allowed." in _message(error)

    def test_tags_and_resource_policy(self, web):
        client = web["client"]
        arn = web["function_arn"]
        client.tag_resource(resource=arn, tags={"team": "web"})
        assert client.list_tags(resource=arn)["tags"] == {"env": "sdk", "team": "web"}
        client.untag_resource(resource=arn, tagKeys=["env"])
        assert client.list_tags(resource=arn)["tags"] == {"team": "web"}

        public_policy = _invoke_policy("*", arn)
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.put_resource_policy(resourceArn=arn, policy=public_policy)
        assert "Lambda blocked the creation of this policy" in _message(error)
        with pytest.raises(client.exceptions.ValidationException) as error:
            client.put_resource_policy(resourceArn=arn, policy="{}")
        assert "Policy has syntax errors." in _message(error)

        account = web["role"].split(":")[4]
        policy = _invoke_policy({"AWS": f"arn:aws:iam::{account}:root"}, arn)
        put = client.put_resource_policy(resourceArn=arn, policy=policy)
        assert put["policy"] == policy
        assert len(put["revisionId"]) == 36
        assert client.get_resource_policy(resourceArn=arn)["revisionId"] == put["revisionId"]
        client.delete_resource_policy(resourceArn=arn, revisionId=put["revisionId"])
        with pytest.raises(client.exceptions.ResourceNotFoundException) as error:
            client.get_resource_policy(resourceArn=arn)
        assert "The function does not have a policy." in _message(error)

    def test_delete_removes_the_function(self, web):
        client = web["client"]
        client.delete_web_function(functionName=FUNCTION_NAME)
        client.get_waiter("web_function_deleted").wait(functionName=FUNCTION_NAME)
        with pytest.raises(client.exceptions.ResourceNotFoundException):
            client.get_web_function(functionName=FUNCTION_NAME)


def _app_zip():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("index.js", "")
    return buf.getvalue()


def _invoke_policy(principal, function_arn):
    return json.dumps({
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": principal,
            "Action": "lambda:InvokeWebFunctionEndpoint",
            "Resource": f"{function_arn}/endpoint/*",
        }],
    }, separators=(",", ":"))


def _message(error):
    return error.value.response["Error"]["Message"]
