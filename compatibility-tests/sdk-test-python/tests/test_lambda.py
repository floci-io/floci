"""Lambda function integration tests."""

import pytest
from botocore.exceptions import ClientError


class TestLambdaFunction:
    """Test Lambda function operations."""

    def test_create_function(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test CreateFunction creates a function."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        try:
            response = lambda_client.create_function(
                FunctionName=fn_name,
                Runtime="nodejs20.x",
                Role=role,
                Handler="index.handler",
                Timeout=30,
                MemorySize=256,
                Code={"ZipFile": minimal_lambda_zip},
            )
            assert response["FunctionName"] == fn_name
            assert response["FunctionArn"]
            assert fn_name in response["FunctionArn"]
            assert response["State"] == "Active"
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_get_function(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test GetFunction returns function details."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.get_function(FunctionName=fn_name)
            assert response["Configuration"]["FunctionName"] == fn_name
            assert response["Configuration"]["Role"] == role
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_get_function_configuration(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        """Test GetFunctionConfiguration returns configuration."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Timeout=30,
            MemorySize=256,
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.get_function_configuration(FunctionName=fn_name)
            assert response["Timeout"] == 30
            assert response["MemorySize"] == 256
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_list_functions(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test ListFunctions includes created function."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.list_functions()
            assert any(f["FunctionName"] == fn_name for f in response["Functions"])
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_update_function_code(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test UpdateFunctionCode updates code."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.update_function_code(
                FunctionName=fn_name, ZipFile=minimal_lambda_zip
            )
            assert response["FunctionName"] == fn_name
            assert response.get("RevisionId")
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_delete_function(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test DeleteFunction removes function."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        lambda_client.delete_function(FunctionName=fn_name)

        with pytest.raises(ClientError) as exc_info:
            lambda_client.get_function(FunctionName=fn_name)
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


class TestLambdaInvoke:
    """Test Lambda invocation."""

    def test_invoke_dry_run(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test Invoke with DryRun returns 204."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.invoke(
                FunctionName=fn_name,
                InvocationType="DryRun",
                Payload=b'{"key":"value"}',
            )
            assert response["StatusCode"] == 204
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_invoke_event_async(self, lambda_client, minimal_lambda_zip, unique_name):
        """Test Invoke with Event type returns 202."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            response = lambda_client.invoke(
                FunctionName=fn_name,
                InvocationType="Event",
                Payload=b'{"key":"async"}',
            )
            assert response["StatusCode"] == 202
        finally:
            lambda_client.delete_function(FunctionName=fn_name)


class TestLambdaErrors:
    """Test Lambda error handling."""

    def test_create_function_duplicate(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        """Test CreateFunction returns 409 for duplicate."""
        fn_name = f"pytest-lambda-{unique_name}"
        role = "arn:aws:iam::000000000000:role/lambda-role"

        lambda_client.create_function(
            FunctionName=fn_name,
            Runtime="nodejs20.x",
            Role=role,
            Handler="index.handler",
            Code={"ZipFile": minimal_lambda_zip},
        )

        try:
            with pytest.raises(ClientError) as exc_info:
                lambda_client.create_function(
                    FunctionName=fn_name,
                    Runtime="nodejs20.x",
                    Role=role,
                    Handler="index.handler",
                    Code={"ZipFile": minimal_lambda_zip},
                )
            assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 409
        finally:
            lambda_client.delete_function(FunctionName=fn_name)

    def test_get_function_not_found(self, lambda_client):
        """Test GetFunction returns 404 for non-existent function."""
        with pytest.raises(ClientError) as exc_info:
            lambda_client.get_function(FunctionName="does-not-exist-pytest")
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
