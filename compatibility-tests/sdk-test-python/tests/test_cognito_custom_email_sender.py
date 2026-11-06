"""Cognito CustomEmailSender trigger, decrypted with the AWS Encryption SDK as a real function would."""

import base64
import io
import json
import time
import zipfile

import aws_encryption_sdk
import botocore.session
import pytest
from aws_encryption_sdk.identifiers import CommitmentPolicy

# Forwards the event it receives to SQS, so the test can read what Cognito sent.
SENDER_CODE = (
    "const { SQSClient, SendMessageCommand } = require('@aws-sdk/client-sqs');\n"
    "exports.handler = async (event) => {\n"
    "  await new SQSClient({ useQueueUrlAsEndpoint: false }).send(new SendMessageCommand({\n"
    "    QueueUrl: process.env.QUEUE_URL, MessageBody: JSON.stringify(event) }));\n"
    "};\n"
)


def _sender_zip():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("index.js", SENDER_CODE)
    return buf.getvalue()


def _next_event(sqs_client, queue_url, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        messages = sqs_client.receive_message(
            QueueUrl=queue_url, MaxNumberOfMessages=1, WaitTimeSeconds=5
        ).get("Messages", [])
        if messages:
            sqs_client.delete_message(
                QueueUrl=queue_url, ReceiptHandle=messages[0]["ReceiptHandle"]
            )
            return json.loads(messages[0]["Body"])
    raise AssertionError("the CustomEmailSender function was not invoked")


@pytest.fixture
def decrypt(aws_config, monkeypatch):
    """Decrypts a code with the Encryption SDK's strict KMS master key provider, KMS being Floci's."""
    monkeypatch.setenv("AWS_ENDPOINT_URL_KMS", aws_config["endpoint_url"])
    session = botocore.session.Session()
    session.set_credentials(
        aws_config["aws_access_key_id"], aws_config["aws_secret_access_key"]
    )
    client = aws_encryption_sdk.EncryptionSDKClient(
        commitment_policy=CommitmentPolicy.REQUIRE_ENCRYPT_ALLOW_DECRYPT
    )

    def _decrypt(code, key_arn):
        provider = aws_encryption_sdk.StrictAwsKmsMasterKeyProvider(
            key_ids=[key_arn], botocore_session=session
        )
        plaintext, header = client.decrypt(source=base64.b64decode(code), key_provider=provider)
        return plaintext.decode("utf-8"), header.encryption_context

    return _decrypt


@pytest.fixture
def sender_pool(cognito_client, kms_client, lambda_client, sqs_client, unique_name):
    queue_url = sqs_client.create_queue(QueueName=f"sender-{unique_name}")["QueueUrl"]
    function_arn = lambda_client.create_function(
        FunctionName=f"sender-{unique_name}",
        Runtime="nodejs20.x",
        Role="arn:aws:iam::000000000000:role/lambda-role",
        Handler="index.handler",
        Timeout=30,
        Code={"ZipFile": _sender_zip()},
        Environment={"Variables": {"QUEUE_URL": queue_url}},
    )["FunctionArn"]
    key_arn = kms_client.create_key(Description="custom sender codes")["KeyMetadata"][
        "Arn"
    ]
    pool_id = cognito_client.create_user_pool(
        PoolName=f"sender-{unique_name}",
        AutoVerifiedAttributes=["email"],
        LambdaConfig={
            "KMSKeyID": key_arn,
            "CustomEmailSender": {"LambdaArn": function_arn, "LambdaVersion": "V1_0"},
        },
    )["UserPool"]["Id"]
    client_id = cognito_client.create_user_pool_client(
        UserPoolId=pool_id,
        ClientName="app",
        ExplicitAuthFlows=["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
    )["UserPoolClient"]["ClientId"]

    yield {
        "pool_id": pool_id,
        "client_id": client_id,
        "key_arn": key_arn,
        "queue_url": queue_url,
    }

    cognito_client.delete_user_pool(UserPoolId=pool_id)
    lambda_client.delete_function(FunctionName=function_arn)
    sqs_client.delete_queue(QueueUrl=queue_url)


class TestCognitoCustomEmailSender:
    @pytest.mark.timeout(240)
    def test_sign_up_and_forgot_password_codes_decrypt_with_the_encryption_sdk(
        self, cognito_client, sqs_client, sender_pool, decrypt
    ):
        pool_id = sender_pool["pool_id"]
        client_id = sender_pool["client_id"]
        cognito_client.sign_up(
            ClientId=client_id,
            Username="alice",
            Password="Passw0rd!",
            UserAttributes=[{"Name": "email", "Value": "alice@example.com"}],
        )

        event = _next_event(sqs_client, sender_pool["queue_url"])
        assert event["triggerSource"] == "CustomEmailSender_SignUp"
        assert event["userPoolId"] == pool_id
        assert event["userName"] == "alice"
        assert event["callerContext"]["clientId"] == client_id
        assert event["request"]["type"] == "customEmailSenderRequestV1"
        assert event["request"]["userAttributes"]["email"] == "alice@example.com"

        code, context = decrypt(event["request"]["code"], sender_pool["key_arn"])
        assert context["userpool-id"] == pool_id
        cognito_client.confirm_sign_up(
            ClientId=client_id, Username="alice", ConfirmationCode=code
        )

        cognito_client.forgot_password(ClientId=client_id, Username="alice")
        event = _next_event(sqs_client, sender_pool["queue_url"])
        assert event["triggerSource"] == "CustomEmailSender_ForgotPassword"
        code, _ = decrypt(event["request"]["code"], sender_pool["key_arn"])
        cognito_client.confirm_forgot_password(
            ClientId=client_id,
            Username="alice",
            ConfirmationCode=code,
            Password="N3wPassw0rd!",
        )

        result = cognito_client.initiate_auth(
            ClientId=client_id,
            AuthFlow="USER_PASSWORD_AUTH",
            AuthParameters={"USERNAME": "alice", "PASSWORD": "N3wPassw0rd!"},
        )
        assert result["AuthenticationResult"]["AccessToken"]
