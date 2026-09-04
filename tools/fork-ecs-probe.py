#!/usr/bin/env python3
"""Workload-side ECS task-role credential contract probe.

The probe deliberately uses a fresh ``boto3.Session()`` with no credential arguments.  It is
run by an ECS task, where the only credential source that should remain is the relative ECS
container-credentials endpoint.  Output contains fingerprints and identity metadata only; bearer
paths and credential values never go to task logs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from typing import Any, NoReturn
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


RELATIVE_URI_RE = re.compile(r"^/v2/credentials/[A-Za-z0-9_-]{32,128}$")
RELATIVE_URI_SEARCH_RE = re.compile(r"/v2/credentials/[A-Za-z0-9_-]{32,128}")
FORBIDDEN_CREDENTIAL_ENV = {
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_SECURITY_TOKEN",
    "AWS_PROFILE",
    "AWS_DEFAULT_PROFILE",
    "AWS_SHARED_CREDENTIALS_FILE",
    "AWS_CONFIG_FILE",
    "AWS_WEB_IDENTITY_TOKEN_FILE",
    "AWS_ROLE_ARN",
    "AWS_CONTAINER_CREDENTIALS_FULL_URI",
    "AWS_CONTAINER_AUTHORIZATION_TOKEN",
    "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
    "BOTO_CONFIG",
}


def fingerprint(value: str) -> str:
    """Return a non-reversible short fingerprint suitable for task logs."""

    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def fail(message: str) -> NoReturn:
    raise RuntimeError(message)


def expect_access_denied(callable_: Any, label: str) -> str:
    """Run one intentionally denied request without retrying or exposing request details."""

    try:
        callable_()
    except ClientError as error:
        code = error.response.get("Error", {}).get("Code")
        if code not in {"AccessDenied", "AccessDeniedException", "UnauthorizedOperation"}:
            fail(label + " returned an unexpected authorization code")
        return str(code)
    fail(label + " unexpectedly succeeded")


def required_environment(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        fail(name + " is required")
    return value


def run_ecs_resource_scope(session: boto3.Session, client_config: Config) -> dict[str, Any]:
    """Prove ECS service ARN scoping using clients from the default task-role session."""

    ecs = session.client("ecs", endpoint_url=os.environ["AWS_ENDPOINT_URL"], config=client_config)
    cluster_arn = required_environment("FORK_ALLOWED_ECS_CLUSTER_ARN")
    cluster_name = required_environment("FORK_ALLOWED_ECS_CLUSTER_NAME")
    allowed_arns = [
        required_environment("FORK_ALLOWED_ECS_SERVICE_A_ARN"),
        required_environment("FORK_ALLOWED_ECS_SERVICE_B_ARN"),
    ]
    forbidden_arn = required_environment("FORK_FORBIDDEN_ECS_SERVICE_ARN")
    foreign_cluster_arn = required_environment("FORK_FOREIGN_ECS_CLUSTER_ARN")
    foreign_service_arn = required_environment("FORK_FOREIGN_ECS_SERVICE_ARN")
    foreign_account_service_arn = required_environment("FORK_FOREIGN_ACCOUNT_ECS_SERVICE_ARN")

    described = ecs.describe_services(cluster=cluster_arn, services=allowed_arns)
    if described.get("failures"):
        fail("allowed ECS DescribeServices returned failures")
    services = described.get("services") or []
    if len(services) != len(allowed_arns):
        fail("allowed ECS DescribeServices returned an unexpected count")
    by_arn = {service.get("serviceArn"): service for service in services}
    if set(by_arn) != set(allowed_arns):
        fail("allowed ECS DescribeServices returned the wrong service set")
    allowed_names: list[str] = []
    for service_arn in allowed_arns:
        service = by_arn[service_arn]
        expected_name = service_arn.rsplit("/", 1)[-1]
        if service.get("serviceName") != expected_name:
            fail("allowed ECS DescribeServices returned the wrong service name")
        allowed_names.append(expected_name)

    short_described = ecs.describe_services(cluster=cluster_name, services=allowed_names)
    if short_described.get("failures"):
        fail("short-name ECS DescribeServices returned failures")
    short_services = short_described.get("services") or []
    if len(short_services) != len(allowed_arns):
        fail("short-name ECS DescribeServices returned an unexpected count")
    short_by_arn = {service.get("serviceArn"): service for service in short_services}
    if set(short_by_arn) != set(allowed_arns):
        fail("short-name ECS DescribeServices returned the wrong service set")
    if any(short_by_arn[service_arn].get("serviceName") != service_name
           for service_arn, service_name in zip(allowed_arns, allowed_names)):
        fail("short-name ECS DescribeServices returned the wrong service name")

    update_results: list[dict[str, Any]] = []
    for service_arn in allowed_arns:
        updated = ecs.update_service(cluster=cluster_arn, service=service_arn, desiredCount=0)
        service = updated.get("service") or {}
        if service.get("serviceArn") != service_arn or service.get("desiredCount") != 0:
            fail("allowed ECS UpdateService returned the wrong service state")
        update_results.append({"service_arn": service_arn, "desired_count": 0})

    short_update_results: list[dict[str, Any]] = []
    for service_arn, service_name in zip(allowed_arns, allowed_names):
        updated = ecs.update_service(cluster=cluster_name, service=service_name, desiredCount=0)
        service = updated.get("service") or {}
        if (service.get("serviceArn") != service_arn
                or service.get("serviceName") != service_name
                or service.get("desiredCount") != 0):
            fail("short-name ECS UpdateService returned the wrong service state")
        short_update_results.append({"service_name": service_name, "service_arn": service_arn})

    mixed_describe_code = expect_access_denied(
        lambda: ecs.describe_services(
            cluster=cluster_arn,
            services=[allowed_arns[0], forbidden_arn],
        ),
        "mixed ECS DescribeServices",
    )
    foreign_cluster_code = expect_access_denied(
        lambda: ecs.describe_services(
            cluster=foreign_cluster_arn,
            services=[foreign_service_arn],
        ),
        "foreign-cluster ECS DescribeServices",
    )
    foreign_account_code = expect_access_denied(
        lambda: ecs.describe_services(
            cluster=cluster_name,
            services=[foreign_account_service_arn],
        ),
        "foreign-account ECS DescribeServices",
    )
    forbidden_update_code = expect_access_denied(
        lambda: ecs.update_service(
            cluster=cluster_arn,
            service=forbidden_arn,
            desiredCount=1,
        ),
        "forbidden ECS UpdateService",
    )
    return {
        "passed": True,
        "allowed_describe": True,
        "allowed_service_arns": allowed_arns,
        "allowed_service_names": allowed_names,
        "short_name_describe": True,
        "allowed_update": update_results,
        "short_name_update": short_update_results,
        "mixed_describe_denied": True,
        "mixed_describe_error_code": mixed_describe_code,
        "foreign_cluster_describe_denied": True,
        "foreign_cluster_describe_error_code": foreign_cluster_code,
        "foreign_account_describe_denied": True,
        "foreign_account_describe_error_code": foreign_account_code,
        "forbidden_update_denied": True,
        "forbidden_update_error_code": forbidden_update_code,
    }


def allowed_endpoints() -> set[str]:
    raw = os.environ.get("FORK_ALLOWED_ENDPOINT_URLS", "")
    values = {item.strip().rstrip("/") for item in raw.split(",") if item.strip()}
    if not values:
        fail("FORK_ALLOWED_ENDPOINT_URLS is required")
    for value in values:
        parsed = urlparse(value)
        if parsed.scheme != "http" or parsed.port != 4566 or parsed.path not in ("", "/"):
            fail("endpoint allowlist contains a non-local endpoint")
        if parsed.hostname in {None, "", "0.0.0.0", "::", "169.254.170.2"}:
            fail("endpoint allowlist contains an invalid host")
        # The runner supplies a Docker-local alias.  Reject public DNS names even when an
        # operator accidentally puts one in the task definition.
        host = parsed.hostname.lower()
        if "." in host and not host.endswith(".local") and host != "localhost":
            fail("endpoint allowlist contains a public-looking hostname")
    return values


def validate_runtime_environment(endpoint: str, relative_uri: str) -> None:
    endpoints = allowed_endpoints()
    if endpoint.rstrip("/") not in endpoints:
        fail("AWS_ENDPOINT_URL is outside the explicit local allowlist")
    parsed = urlparse(endpoint)
    if parsed.scheme != "http" or parsed.port != 4566 or parsed.hostname in {
        None,
        "",
        "127.0.0.1",
        "localhost",
        "169.254.170.2",
    }:
        # A task must reach Floci through its private Docker alias, not its metadata address or
        # an endpoint on the runner host.  The control driver is the only localhost client.
        fail("task endpoint is not a private Docker-local alias")
    if not RELATIVE_URI_RE.fullmatch(relative_uri):
        fail("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI has an invalid shape")
    if os.environ.get("AWS_EC2_METADATA_DISABLED") != "true":
        fail("AWS_EC2_METADATA_DISABLED must be true for a task-role workload")
    for key in FORBIDDEN_CREDENTIAL_ENV:
        if os.environ.get(key):
            fail(f"static or alternate credential source remains enabled: {key}")


def metadata_status(path: str) -> int:
    """Check an unknown metadata path without exposing that path in output."""

    request = urllib.request.Request(f"http://169.254.170.2{path}", method="GET")
    try:
        with urllib.request.urlopen(request, timeout=4) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code


def run_probe(check_unknown: bool, idle_seconds: int = 0, resource_scope: bool = False) -> dict[str, Any]:
    relative_uri = os.environ.get("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "")
    endpoint = os.environ.get("AWS_ENDPOINT_URL", "")
    validate_runtime_environment(endpoint, relative_uri)

    # This is intentionally the default provider chain.  Do not add explicit key arguments here:
    # the contract is specifically that boto3 reports the ECS ``container-role`` provider.
    session = boto3.Session()
    credentials = session.get_credentials()
    if credentials is None:
        fail("boto3 default chain returned no credentials")
    if credentials.method != "container-role":
        fail(f"boto3 selected {credentials.method!r}, expected container-role")

    client_config = Config(
        connect_timeout=4,
        read_timeout=8,
        retries={"total_max_attempts": 1, "mode": "standard"},
    )
    sts = session.client("sts", endpoint_url=endpoint, config=client_config)
    identity = sts.get_caller_identity()
    account = identity.get("Account")
    arn = identity.get("Arn")
    expected_role = os.environ.get("FORK_EXPECTED_ROLE_ARN", "")
    expected_account = expected_role.split(":")[4] if expected_role.count(":") >= 5 else ""
    expected_role_name = expected_role.rsplit("/", 1)[-1] if "/" in expected_role else ""
    if not account or account != expected_account:
        fail("STS returned an unexpected account")
    if not arn or ":assumed-role/" not in arn or f"/{expected_role_name}/" not in arn:
        fail("STS did not identify the configured ECS task role")

    s3 = session.client("s3", endpoint_url=endpoint, config=client_config)
    response = s3.get_object(Bucket=os.environ["FORK_ALLOWED_BUCKET"], Key=os.environ["FORK_ALLOWED_KEY"])
    with response["Body"] as body:
        if body.read() != b"fork-ecs-runtime-contract":
            fail("allowed S3 object returned unexpected content")
    try:
        s3.list_buckets()
    except ClientError as error:
        denied_code = error.response.get("Error", {}).get("Code")
        if denied_code not in {"AccessDenied", "AccessDeniedException"}:
            fail(f"S3 denial returned unexpected code {denied_code!r}")
    else:
        fail("S3 list_buckets unexpectedly succeeded for the deny-only task role")

    initial_key_fingerprint = fingerprint(credentials.access_key)
    resource_scope_result = None
    if resource_scope:
        resource_scope_result = run_ecs_resource_scope(session, client_config)
    if idle_seconds:
        # Keep the actual SDK clients and their credential cache alive across a full lease TTL.
        # Do not construct a replacement session/client or inject replacement credentials.
        time.sleep(idle_seconds)
        resumed_identity = sts.get_caller_identity()
        if resumed_identity.get("Account") != account or resumed_identity.get("Arn") != arn:
            fail("cached STS client changed task identity after idle")
        response = s3.get_object(Bucket=os.environ["FORK_ALLOWED_BUCKET"], Key=os.environ["FORK_ALLOWED_KEY"])
        with response["Body"] as body:
            if body.read() != b"fork-ecs-runtime-contract":
                fail("cached S3 client could not read the allowed object after idle")
        if fingerprint(credentials.access_key) == initial_key_fingerprint:
            fail("cached SDK client retained its expired key after idle")

    result: dict[str, Any] = {
        "credential_provider": credentials.method,
        "access_key_fingerprint": fingerprint(credentials.access_key),
        "credential_path_fingerprint": fingerprint(relative_uri),
        "account": account,
        "role_arn": arn,
        "endpoint": endpoint.rstrip("/"),
        "s3_denied": True,
        "s3_object_allowed": True,
    }
    expiry = getattr(credentials, "_expiry_time", None)
    if expiry is not None:
        result["credential_expiry"] = expiry.isoformat()
    if idle_seconds:
        result["cached_client_continuity"] = True
        result["cached_client_idle_seconds"] = idle_seconds
        result["cached_client_initial_key_fingerprint"] = initial_key_fingerprint
    if resource_scope_result is not None:
        result["ecs_resource_scope"] = resource_scope_result
    if check_unknown:
        unknown_token = "Z" * 48
        status = metadata_status(f"/v2/credentials/{unknown_token}")
        if status != 404:
            fail(f"unknown metadata token returned HTTP {status}, expected 404")
        result["unknown_metadata_status"] = status
        result["unknown_metadata_denied"] = True
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idle-seconds", type=int, choices=(0, 125), default=0,
                        help="reuse the same SDK clients after the contract's 120-second TTL")
    parser.add_argument(
        "--once",
        action="store_true",
        help="run the assertions once and exit instead of holding the ECS task",
    )
    parser.add_argument(
        "--unknown",
        action="store_true",
        help="also assert an unknown metadata token is denied",
    )
    parser.add_argument(
        "--resource-scope",
        action="store_true",
        help="assert task-role ECS service ARN allow/deny boundaries",
    )
    args = parser.parse_args()
    result = run_probe(args.unknown, args.idle_seconds, args.resource_scope)
    print(json.dumps(result, sort_keys=True), flush=True)
    if not args.once:
        hold_seconds = int(os.environ.get("FORK_PROBE_HOLD_SECONDS", "90"))
        if hold_seconds < 1 or hold_seconds > 3600:
            fail("FORK_PROBE_HOLD_SECONDS must be between 1 and 3600")
        time.sleep(hold_seconds)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Never let an SDK/Docker diagnostic echo the bearer URI or temporary access key.
        safe = RELATIVE_URI_SEARCH_RE.sub("/v2/credentials/<redacted>", str(error))
        safe = re.sub(r"ASIAECS[A-Z0-9]{13}", "ASIAECS<redacted>", safe)
        print(f"fork ECS probe failed: {safe}", file=sys.stderr)
        sys.exit(1)
