"""#471 — FunctionConfiguration fields compatibility tests.

Verifies that CreateFunction and UpdateFunctionConfiguration accept and round-trip
Architectures, EphemeralStorage, TracingConfig, DeadLetterConfig, Environment,
CodeSha256, and LastModified via the AWS SDK for Python (boto3).
"""

import re
import pytest
from botocore.exceptions import ClientError

ROLE = "arn:aws:iam::000000000000:role/lambda-role"

# ISO-8601 pattern AWS Lambda returns: 2024-01-15T10:30:00.000+0000
_LAST_MODIFIED_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{4}$")


class TestFunctionConfigurationDefaults:
    """createFunction response includes all required fields with correct defaults."""

    def test_create_function_has_code_sha256(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            resp = lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            assert resp.get("CodeSha256"), "CodeSha256 must be a non-empty string"
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_create_function_has_iso8601_last_modified(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            resp = lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            last_modified = resp.get("LastModified", "")
            assert _LAST_MODIFIED_RE.match(last_modified), (
                f"LastModified must match yyyy-MM-dd'T'HH:mm:ss.SSSZ, got: {last_modified!r}"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_get_function_configuration_default_architectures(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.get_function_configuration(FunctionName=fn)
            assert resp.get("Architectures") == ["x86_64"], (
                f"default Architectures must be ['x86_64'], got: {resp.get('Architectures')}"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_get_function_configuration_default_ephemeral_storage(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.get_function_configuration(FunctionName=fn)
            ephemeral = resp.get("EphemeralStorage")
            assert ephemeral is not None, "EphemeralStorage must always be present"
            assert ephemeral.get("Size") == 512, (
                f"default EphemeralStorage.Size must be 512, got: {ephemeral.get('Size')}"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_get_function_configuration_default_tracing_config(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.get_function_configuration(FunctionName=fn)
            tracing = resp.get("TracingConfig")
            assert tracing is not None, "TracingConfig must always be present"
            assert tracing.get("Mode") == "PassThrough", (
                f"default TracingConfig.Mode must be PassThrough, got: {tracing.get('Mode')}"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_get_function_configuration_environment_always_present(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.get_function_configuration(FunctionName=fn)
            assert "Environment" in resp, (
                "Environment block must always be present in GetFunctionConfiguration response"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)


class TestCreateFunctionWithNewFields:
    """createFunction accepts and persists new fields."""

    def test_create_function_with_arm64_architecture(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            resp = lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Architectures=["arm64"],
                Code={"ZipFile": minimal_lambda_zip},
            )
            assert resp.get("Architectures") == ["arm64"], (
                f"Architectures must be ['arm64'], got: {resp.get('Architectures')}"
            )
            # Verify it persists
            cfg = lambda_client.get_function_configuration(FunctionName=fn)
            assert cfg.get("Architectures") == ["arm64"]
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_create_function_with_ephemeral_storage(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            resp = lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                EphemeralStorage={"Size": 2048},
                Code={"ZipFile": minimal_lambda_zip},
            )
            assert resp.get("EphemeralStorage", {}).get("Size") == 2048
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_create_function_with_tracing_config(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            resp = lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                TracingConfig={"Mode": "Active"},
                Code={"ZipFile": minimal_lambda_zip},
            )
            assert resp.get("TracingConfig", {}).get("Mode") == "Active"
        finally:
            lambda_client.delete_function(FunctionName=fn)


class TestUpdateFunctionConfiguration:
    """updateFunctionConfiguration round-trips new fields."""

    def test_update_ephemeral_storage_and_tracing(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.update_function_configuration(
                FunctionName=fn,
                Timeout=60,
                EphemeralStorage={"Size": 1024},
                TracingConfig={"Mode": "Active"},
            )
            assert resp.get("Timeout") == 60
            assert resp.get("EphemeralStorage", {}).get("Size") == 1024
            assert resp.get("TracingConfig", {}).get("Mode") == "Active"

            # Verify persistence
            cfg = lambda_client.get_function_configuration(FunctionName=fn)
            assert cfg.get("EphemeralStorage", {}).get("Size") == 1024
            assert cfg.get("TracingConfig", {}).get("Mode") == "Active"
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_update_environment_variables_round_trip(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            resp = lambda_client.update_function_configuration(
                FunctionName=fn,
                Environment={"Variables": {"KEY_A": "value-a", "KEY_B": "value-b"}},
            )
            variables = resp.get("Environment", {}).get("Variables", {})
            assert variables.get("KEY_A") == "value-a"
            assert variables.get("KEY_B") == "value-b"

            # Clear environment — block must still be present
            cleared = lambda_client.update_function_configuration(
                FunctionName=fn,
                Environment={"Variables": {}},
            )
            assert "Environment" in cleared, (
                "Environment block must be present even after clearing variables"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)

    def test_stale_revision_id_returns_412(
        self, lambda_client, minimal_lambda_zip, unique_name
    ):
        fn = f"pytest-cfg-{unique_name}"
        try:
            lambda_client.create_function(
                FunctionName=fn,
                Runtime="nodejs20.x",
                Role=ROLE,
                Handler="index.handler",
                Code={"ZipFile": minimal_lambda_zip},
            )
            with pytest.raises(ClientError) as exc_info:
                lambda_client.update_function_configuration(
                    FunctionName=fn,
                    Timeout=10,
                    RevisionId="00000000-0000-0000-0000-000000000000",
                )
            assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 412, (
                "Stale RevisionId must return 412 PreconditionFailedException"
            )
        finally:
            lambda_client.delete_function(FunctionName=fn)
