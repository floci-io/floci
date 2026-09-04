#!/usr/bin/env python3
"""Deterministic, disposable ECS task-role credential contract.

This runner deliberately keeps all AWS API traffic inside the disposable Floci container.  The
host only drives Docker and reads redacted probe results.  It never publishes a Floci or task
port, and it never prints a bearer path or credential value.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any


CONTROL_DRIVER = r'''#!/usr/bin/env python3
"""Private control-plane driver copied into the disposable Floci container."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


CONTROL_ENDPOINT = "http://localhost:4566"
REGION = "us-east-1"
ACCOUNT = "000000000000"
CONTROL_ACCESS_KEY = ACCOUNT
CONTROL_SECRET = "test"
CONTROL_TOKEN = "test"
PATH_RE = re.compile(r"^/v2/credentials/[A-Za-z0-9_-]{32,128}$")
PATH_SEARCH_RE = re.compile(r"/v2/credentials/[A-Za-z0-9_-]{32,128}")
LABEL_RE = re.compile(r"^[A-Za-z0-9_.-]{1,40}$")
CAPTURE_ROOT = Path("/tmp")


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def clients(*, read_timeout: int = 8):
    # Control calls use public synthetic credentials and the loopback endpoint only.  They are
    # intentionally separate from the workload's default boto3 provider chain.
    session = boto3.Session(
        aws_access_key_id=CONTROL_ACCESS_KEY,
        aws_secret_access_key=CONTROL_SECRET,
        aws_session_token=CONTROL_TOKEN,
        region_name=REGION,
    )
    config = Config(
        connect_timeout=4,
        read_timeout=read_timeout,
        retries={"total_max_attempts": 1, "mode": "standard"},
    )
    return session.client("iam", endpoint_url=CONTROL_ENDPOINT, config=config), session.client(
        "ecs", endpoint_url=CONTROL_ENDPOINT, config=config
    ), session.client("s3", endpoint_url=CONTROL_ENDPOINT, config=config)


def setup(payload: dict[str, Any]) -> dict[str, Any]:
    role_name = payload["role_name"]
    cluster_name = payload["cluster_name"]
    service_names = payload["service_names"]
    foreign_cluster_name = payload["foreign_cluster_name"]
    family = payload["family"]
    probe_image = payload["probe_image"]
    endpoint_allowlist = payload["endpoint_allowlist"]
    bucket = payload["bucket"]
    object_key = payload["object_key"]
    iam, ecs, s3 = clients()

    trust = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {"Service": "ecs-tasks.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }
        ],
    }
    role = iam.create_role(
        RoleName=role_name,
        AssumeRolePolicyDocument=json.dumps(trust, separators=(",", ":")),
        Description="Disposable ECS task-role credential contract",
    )
    policy_name = "contract-s3-policy"
    policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "AllowOnlyContractObjectRead",
                "Effect": "Allow",
                "Action": "s3:GetObject",
                "Resource": f"arn:aws:s3:::{bucket}/{object_key}",
            },
            {
                "Sid": "DenyBucketEnumerationForContract",
                "Effect": "Deny",
                "Action": "s3:ListAllMyBuckets",
                "Resource": "*",
            },
        ],
    }
    # Seed one object that the task role is explicitly allowed to read.  The role's
    # ListAllMyBuckets action remains explicitly denied, giving the probe both a positive
    # authorization check and a deliberate denial check without treating a deny-only role as
    # proof that credentials are active.
    s3.create_bucket(Bucket=bucket)
    s3.put_object(Bucket=bucket, Key=object_key, Body=b"fork-ecs-runtime-contract")
    cluster = ecs.create_cluster(clusterName=cluster_name)["cluster"]
    foreign_cluster = ecs.create_cluster(clusterName=foreign_cluster_name)["cluster"]

    def service_arn(cluster_arn: str, service_name: str) -> str:
        marker = ":cluster/"
        if marker not in cluster_arn:
            raise RuntimeError("ECS cluster ARN has an unexpected shape")
        return cluster_arn.replace(marker, ":service/", 1) + "/" + service_name

    primary_service_arns = {
        key: service_arn(cluster["clusterArn"], service_names[key])
        for key in ("allowed_a", "allowed_b", "forbidden")
    }
    foreign_service_arn = service_arn(foreign_cluster["clusterArn"], service_names["foreign"])
    # Deliberately use a valid ECS service ARN with a different account.  The service does not
    # exist; IAM must reject it by resource scope before the ECS handler can report a missing
    # service, proving account identity is part of the authorization decision.
    foreign_account_service_arn = (
        f"arn:aws:ecs:{REGION}:999999999999:service/{cluster_name}/{service_names['foreign']}"
    )
    task_definition_args: dict[str, Any] = {
        "family": family,
        "taskRoleArn": role["Role"]["Arn"],
        "networkMode": "awsvpc",
        "requiresCompatibilities": ["FARGATE"],
        "cpu": "256",
        "memory": "512",
        "containerDefinitions": [
            {
                "name": "probe",
                "image": probe_image,
                "essential": True,
                "environment": [
                    {"name": "FORK_ALLOWED_ENDPOINT_URLS", "value": endpoint_allowlist},
                    {"name": "FORK_EXPECTED_ROLE_ARN", "value": role["Role"]["Arn"]},
                    {"name": "FORK_ALLOWED_BUCKET", "value": bucket},
                    {"name": "FORK_ALLOWED_KEY", "value": object_key},
                    {"name": "FORK_ALLOWED_ECS_CLUSTER_ARN", "value": cluster["clusterArn"]},
                    {"name": "FORK_ALLOWED_ECS_CLUSTER_NAME", "value": cluster_name},
                    {"name": "FORK_ALLOWED_ECS_SERVICE_A_ARN", "value": primary_service_arns["allowed_a"]},
                    {"name": "FORK_ALLOWED_ECS_SERVICE_B_ARN", "value": primary_service_arns["allowed_b"]},
                    {"name": "FORK_FORBIDDEN_ECS_SERVICE_ARN", "value": primary_service_arns["forbidden"]},
                    {"name": "FORK_FOREIGN_ECS_CLUSTER_ARN", "value": foreign_cluster["clusterArn"]},
                    {"name": "FORK_FOREIGN_ECS_SERVICE_ARN", "value": foreign_service_arn},
                    {
                        "name": "FORK_FOREIGN_ACCOUNT_ECS_SERVICE_ARN",
                        "value": foreign_account_service_arn,
                    },
                    {"name": "FORK_PROBE_HOLD_SECONDS", "value": "900"},
                    {
                        "name": "NO_PROXY",
                        "value": "169.254.170.2," + payload["floci_name"] + ",127.0.0.1,localhost",
                    },
                ],
            }
        ],
    }
    if payload.get("platform"):
        task_definition_args["runtimePlatform"] = {
            "cpuArchitecture": "ARM64" if payload["platform"] == "arm64" else "X86_64",
            "operatingSystemFamily": "LINUX",
        }
    task_definition = ecs.register_task_definition(**task_definition_args)["taskDefinition"]

    def create_service(cluster_arn: str, service_name: str) -> dict[str, Any]:
        service = ecs.create_service(
            cluster=cluster_arn,
            serviceName=service_name,
            taskDefinition=task_definition["taskDefinitionArn"],
            desiredCount=0,
            launchType="FARGATE",
        )["service"]
        if service.get("serviceArn") is None or service.get("serviceName") != service_name:
            raise RuntimeError("ECS service creation returned an unexpected identity")
        return service

    primary_services = {
        key: create_service(cluster["clusterArn"], service_names[key])
        for key in ("allowed_a", "allowed_b", "forbidden")
    }
    foreign_service = create_service(foreign_cluster["clusterArn"], service_names["foreign"])
    if any(primary_services[key].get("serviceArn") != primary_service_arns[key]
           for key in primary_services):
        raise RuntimeError("ECS service ARN did not match its cluster-scoped identity")
    if foreign_service.get("serviceArn") != foreign_service_arn:
        raise RuntimeError("foreign ECS service ARN did not match its cluster-scoped identity")

    policy["Statement"].append(
        {
            "Sid": "AllowScopedEcsServiceControl",
            "Effect": "Allow",
            "Action": ["ecs:DescribeServices", "ecs:UpdateService"],
            "Resource": [primary_service_arns["allowed_a"], primary_service_arns["allowed_b"]],
        }
    )
    iam.put_role_policy(
        RoleName=role_name,
        PolicyName=policy_name,
        PolicyDocument=json.dumps(policy, separators=(",", ":")),
    )
    return {
        "role_arn": role["Role"]["Arn"],
        "role_name": role_name,
        "policy_name": policy_name,
        "cluster_arn": cluster["clusterArn"],
        "task_definition_arn": task_definition["taskDefinitionArn"],
        "task_image": task_definition["containerDefinitions"][0]["image"],
        "bucket": bucket,
        "object_key": object_key,
        "services": {
            "allowed_a": {"name": service_names["allowed_a"], "arn": primary_service_arns["allowed_a"]},
            "allowed_b": {"name": service_names["allowed_b"], "arn": primary_service_arns["allowed_b"]},
            "forbidden": {"name": service_names["forbidden"], "arn": primary_service_arns["forbidden"]},
            "foreign": {"name": service_names["foreign"], "arn": foreign_service_arn},
        },
        "foreign_cluster_arn": foreign_cluster["clusterArn"],
        "foreign_account_service_arn": foreign_account_service_arn,
        "runtime_platform": task_definition.get("runtimePlatform"),
        "ttl_seconds": 120,
        "refresh_window_seconds": 60,
    }


def run_tasks(payload: dict[str, Any]) -> dict[str, Any]:
    # RunTask synchronously starts three real containers. QEMU cold starts can exceed
    # the normal API read deadline; keep one attempt so a timeout never duplicates tasks.
    _, ecs, _ = clients(read_timeout=60)
    response = ecs.run_task(
        cluster=payload["cluster_arn"],
        taskDefinition=payload["task_definition_arn"],
        count=3,
        launchType="FARGATE",
    )
    failures = response.get("failures", [])
    if failures:
        raise RuntimeError("RunTask returned failures")
    tasks = response.get("tasks", [])
    if len(tasks) != 3:
        raise RuntimeError(f"RunTask returned {len(tasks)} tasks, expected 3")
    return {
        "tasks": [
            {"task_arn": task["taskArn"], "last_status": task.get("lastStatus")}
            for task in tasks
        ]
    }


def _metadata(path: str) -> tuple[int, dict[str, Any] | None]:
    if not PATH_RE.fullmatch(path):
        raise ValueError("invalid credential path")
    request = urllib.request.Request("http://169.254.170.2" + path, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=4) as response:
            body = response.read()
            return response.status, json.loads(body)
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, None


def metadata(payload: dict[str, Any]) -> dict[str, Any]:
    status, body = _metadata(payload["path"])
    result: dict[str, Any] = {"status": status}
    if status == 200 and body is not None:
        access_key = body.get("AccessKeyId")
        token = body.get("Token")
        expiration = body.get("Expiration")
        role_arn = body.get("RoleArn")
        if not all(isinstance(value, str) and value for value in (access_key, token, expiration, role_arn)):
            raise RuntimeError("metadata response omitted required fields")
        result.update(
            {
                "access_key_fingerprint": fingerprint(access_key),
                "session_token_fingerprint": fingerprint(token),
                "path_fingerprint": fingerprint(payload["path"]),
                "expiration": expiration,
                "role_arn": role_arn,
            }
        )
    return result


def capture(payload: dict[str, Any]) -> dict[str, Any]:
    label = payload["label"]
    if not LABEL_RE.fullmatch(label):
        raise ValueError("invalid capture label")
    status, body = _metadata(payload["path"])
    if status != 200 or body is None:
        return {"status": status, "captured": False}
    required = ("AccessKeyId", "SecretAccessKey", "Token", "Expiration", "RoleArn")
    if any(not isinstance(body.get(key), str) or not body[key] for key in required):
        raise RuntimeError("metadata response omitted capture fields")
    capture_path = CAPTURE_ROOT / ("fork-ecs-capture-" + label + ".json")
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    fd = os.open(capture_path, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump({key: body[key] for key in required}, handle, separators=(",", ":"))
    except Exception:
        try:
            os.close(fd)
        except OSError:
            pass
        raise
    return {
        "status": status,
        "captured": True,
        "access_key_fingerprint": fingerprint(body["AccessKeyId"]),
        "path_fingerprint": fingerprint(payload["path"]),
        "expiration": body["Expiration"],
        "role_arn": body["RoleArn"],
    }


def replay(payload: dict[str, Any]) -> dict[str, Any]:
    label = payload["label"]
    if not LABEL_RE.fullmatch(label):
        raise ValueError("invalid capture label")
    capture_path = CAPTURE_ROOT / ("fork-ecs-capture-" + label + ".json")
    with capture_path.open(encoding="utf-8") as handle:
        credentials = json.load(handle)
    session = boto3.Session(
        aws_access_key_id=credentials["AccessKeyId"],
        aws_secret_access_key=credentials["SecretAccessKey"],
        aws_session_token=credentials["Token"],
        region_name=REGION,
    )
    client = session.client("s3", endpoint_url=CONTROL_ENDPOINT)
    try:
        response = client.get_object(Bucket=payload["bucket"], Key=payload["object_key"])
        body = response.get("Body")
        if body is not None:
            body.read()
            body.close()
    except ClientError as error:
        code = error.response.get("Error", {}).get("Code") or "ClientError"
        if code not in {"AccessDenied", "AccessDeniedException", "InvalidAccessKeyId",
                        "InvalidClientTokenId", "ExpiredToken", "ExpiredTokenException"}:
            raise RuntimeError("replay returned non-auth failure: " + code)
        return {"denied": True, "error_code": code}
    return {"denied": False, "allowed": True, "error_code": None}


def _capture_summary(label: str, path: str) -> dict[str, Any]:
    if not LABEL_RE.fullmatch(label):
        raise ValueError("invalid capture label")
    capture_path = CAPTURE_ROOT / ("fork-ecs-capture-" + label + ".json")
    with capture_path.open(encoding="utf-8") as handle:
        credentials = json.load(handle)
    required = ("AccessKeyId", "SecretAccessKey", "Token", "Expiration", "RoleArn")
    if any(not isinstance(credentials.get(key), str) or not credentials[key] for key in required):
        raise RuntimeError("capture omitted required fields")
    return {
        "access_key_fingerprint": fingerprint(credentials["AccessKeyId"]),
        "path_fingerprint": fingerprint(path),
        "expiration": credentials["Expiration"],
        "role_arn": credentials["RoleArn"],
    }


def _expiration_epoch(value: Any) -> float:
    if not isinstance(value, str) or not value:
        raise RuntimeError("capture expiration is missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise RuntimeError("capture expiration is not ISO-8601") from error
    if parsed.tzinfo is None:
        raise RuntimeError("capture expiration has no timezone")
    return parsed.timestamp()


def _assert_allowed(value: dict[str, Any], label: str) -> None:
    if value.get("denied") is not False or value.get("allowed") is not True:
        raise RuntimeError(label + " did not remain usable before expiration")


def _assert_stopped_denial(value: dict[str, Any], label: str) -> None:
    if value.get("denied") is not True or value.get("allowed") is not None:
        raise RuntimeError(label + " remained usable after task stop")
    if value.get("error_code") in {"ExpiredToken", "ExpiredTokenException"}:
        raise RuntimeError(label + " denial was caused by expiration, not task stop")


def overlap(payload: dict[str, Any]) -> dict[str, Any]:
    """Prove generation overlap, then revoke both generations with one exact task stop."""

    initial_label = payload.get("initial_label", "initial")
    rotated_label = payload.get("rotated_label", "rotated")
    initial = _capture_summary(initial_label, payload["path"])
    rotated = _capture_summary(rotated_label, payload["path"])
    if initial["path_fingerprint"] != rotated["path_fingerprint"]:
        raise RuntimeError("credential URI changed during generation rotation")
    if initial["access_key_fingerprint"] == rotated["access_key_fingerprint"]:
        raise RuntimeError("generation rotation did not produce a new access key")
    initial_expiration = _expiration_epoch(initial["expiration"])
    rotated_expiration = _expiration_epoch(rotated["expiration"])
    minimum_remaining = int(payload.get("minimum_remaining_seconds", 10))
    if minimum_remaining < 1 or minimum_remaining > 60:
        raise ValueError("minimum overlap lifetime must be between 1 and 60 seconds")
    before_stop_remaining = min(initial_expiration, rotated_expiration) - time.time()
    if before_stop_remaining <= minimum_remaining:
        raise RuntimeError("generation overlap did not retain both credentials before expiry")

    object_ref = {"bucket": payload["bucket"], "object_key": payload["object_key"]}
    initial_replay = replay({"label": initial_label, **object_ref})
    _assert_allowed(initial_replay, "initial generation")
    rotated_replay = replay({"label": rotated_label, **object_ref})
    _assert_allowed(rotated_replay, "rotated generation")

    _, ecs, _ = clients(read_timeout=60)
    ecs.stop_task(
        cluster=payload["cluster_arn"],
        task=payload["task_arn"],
        reason="credential overlap contract stop",
    )
    stop_deadline = time.monotonic() + 15
    stopped_status = None
    while time.monotonic() < stop_deadline:
        stopped_status, _ = _metadata(payload["path"])
        if stopped_status == 404:
            break
        if stopped_status != 200:
            raise RuntimeError("stopped task credential URI returned an unexpected status")
        time.sleep(0.25)
    if stopped_status != 404:
        raise RuntimeError("stopped task credential URI remained live")
    after_stop_remaining = min(initial_expiration, rotated_expiration) - time.time()
    if after_stop_remaining <= 0:
        raise RuntimeError("generation stop denials could be explained by expiration")

    stopped_initial_replay = replay({"label": initial_label, **object_ref})
    _assert_stopped_denial(stopped_initial_replay, "initial generation")
    stopped_rotated_replay = replay({"label": rotated_label, **object_ref})
    _assert_stopped_denial(stopped_rotated_replay, "rotated generation")
    after_replay_remaining = min(initial_expiration, rotated_expiration) - time.time()
    if after_replay_remaining <= 0:
        raise RuntimeError("generation stop denials finished after credential expiration")
    return {
        "overlap": True,
        "initial": initial,
        "rotated": rotated,
        "initial_replay": initial_replay,
        "rotated_replay": rotated_replay,
        "before_stop_remaining_seconds": round(before_stop_remaining, 3),
        "after_stop_remaining_seconds": round(after_stop_remaining, 3),
        "after_replay_remaining_seconds": round(after_replay_remaining, 3),
        "stopped_metadata_status": stopped_status,
        "stopped_initial_replay": stopped_initial_replay,
        "stopped_rotated_replay": stopped_rotated_replay,
    }


def stop_tasks(payload: dict[str, Any]) -> dict[str, Any]:
    _, ecs, _ = clients()
    errors: list[str] = []
    for task_arn in payload.get("task_arns", []):
        try:
            ecs.stop_task(cluster=payload["cluster_arn"], task=task_arn, reason="credential contract cleanup")
        except ClientError as error:
            code = error.response.get("Error", {}).get("Code", "ClientError")
            if code not in {"InvalidParameterException", "ClusterNotFoundException", "ResourceNotFoundException"}:
                errors.append(code)
    if errors:
        raise RuntimeError("StopTask cleanup failed")
    return {"stopped": True}


def service_state(payload: dict[str, Any]) -> dict[str, Any]:
    """Read one exact service with control credentials for denied-update readback."""

    _, ecs, _ = clients()
    response = ecs.describe_services(
        cluster=payload["cluster_arn"],
        services=[payload["service_arn"]],
    )
    services = response.get("services") or []
    if len(services) != 1 or response.get("failures"):
        raise RuntimeError("ECS service readback did not resolve exactly one service")
    service = services[0]
    if service.get("serviceArn") != payload["service_arn"]:
        raise RuntimeError("ECS service readback returned an unexpected ARN")
    return {
        "service_arn": service["serviceArn"],
        "service_name": service.get("serviceName"),
        "desired_count": service.get("desiredCount"),
        "status": service.get("status"),
    }


def cleanup(payload: dict[str, Any]) -> dict[str, Any]:
    iam, ecs, s3 = clients()
    errors: list[str] = []

    def call(label: str, function) -> None:
        try:
            function()
        except ClientError as error:
            code = error.response.get("Error", {}).get("Code", "ClientError")
            if code not in {
                "NoSuchEntity",
                "ResourceNotFoundException",
                "ClusterNotFoundException",
                "ServiceNotFoundException",
                "NoSuchBucket",
                "NoSuchKey",
                "NotFound",
                "404",
            }:
                errors.append(label + ":" + code)

    for label in ("initial", "rotated", "task1", "task2", "resumed"):
        capture_path = CAPTURE_ROOT / ("fork-ecs-capture-" + label + ".json")
        try:
            capture_path.unlink()
        except FileNotFoundError:
            pass
    call("delete-object", lambda: s3.delete_object(Bucket=payload["bucket"], Key=payload["object_key"]))
    call("delete-bucket", lambda: s3.delete_bucket(Bucket=payload["bucket"]))
    # Services must be inactive before their exact clusters can be removed. All services have
    # desiredCount=0, but force=True keeps teardown deterministic if a failed probe changed one.
    services = payload.get("services") or {}
    foreign_cluster_arn = payload.get("foreign_cluster_arn")
    for key, service in services.items():
        cluster_arn = foreign_cluster_arn if key == "foreign" else payload["cluster_arn"]
        if not cluster_arn or not isinstance(service, dict) or not service.get("arn"):
            raise RuntimeError("cleanup service identity is incomplete")
        call(
            "delete-service-" + key,
            lambda cluster_arn=cluster_arn, service_arn=service["arn"]: ecs.delete_service(
                cluster=cluster_arn,
                service=service_arn,
                force=True,
            ),
        )
    call(
        "deregister-task-definition",
        lambda: ecs.deregister_task_definition(taskDefinition=payload["task_definition_arn"]),
    )
    call(
        "delete-task-definition",
        lambda: ecs.delete_task_definitions(taskDefinitions=[payload["task_definition_arn"]]),
    )
    call(
        "delete-role-policy",
        lambda: iam.delete_role_policy(RoleName=payload["role_name"], PolicyName=payload["policy_name"]),
    )
    call("delete-role", lambda: iam.delete_role(RoleName=payload["role_name"]))
    if foreign_cluster_arn:
        call("delete-foreign-cluster", lambda: ecs.delete_cluster(cluster=foreign_cluster_arn))
    call("delete-cluster", lambda: ecs.delete_cluster(cluster=payload["cluster_arn"]))
    if errors:
        raise RuntimeError("cleanup failed")
    return {"cleaned": True}


def main() -> int:
    if len(sys.argv) != 2:
        raise ValueError("operation is required")
    payload = json.loads(sys.stdin.read() or "{}")
    operation = sys.argv[1]
    result = {
        "setup": setup,
        "run": run_tasks,
        "metadata": metadata,
        "capture": capture,
        "replay": replay,
        "overlap": overlap,
        "stop": stop_tasks,
        "service_state": service_state,
        "cleanup": cleanup,
    }.get(operation)
    if result is None:
        raise ValueError("unknown operation")
    print(json.dumps(result(payload), sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        safe = PATH_SEARCH_RE.sub("/v2/credentials/<redacted>", str(error))
        safe = re.sub(r"ASIAECS[A-Z0-9]{13}", "ASIAECS<redacted>", safe)
        print(f"fork ECS control failed: {safe}", file=sys.stderr)
        sys.exit(1)
'''


PATH_RE = re.compile(r"^/v2/credentials/[A-Za-z0-9_-]{32,128}$")
PATH_SEARCH_RE = re.compile(r"/v2/credentials/[A-Za-z0-9_-]{32,128}")
ACCESS_KEY_RE = re.compile(r"ASIAECS[A-Z0-9]{13}")
FORBIDDEN_TASK_ENV = {
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


class ContractFailure(RuntimeError):
    """A deterministic contract assertion failed."""


def redact(value: str) -> str:
    # PATH_RE validates a complete environment value; PATH_SEARCH_RE also removes a bearer
    # path when a Docker/HTTP error embeds it in a URL or longer diagnostic string.
    value = PATH_SEARCH_RE.sub("/v2/credentials/<redacted>", value)
    return ACCESS_KEY_RE.sub("ASIAECS<redacted>", value)


def docker_json(*args: str) -> Any:
    completed = subprocess.run(
        ["docker", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        detail = redact(completed.stderr.strip())
        raise ContractFailure(f"docker {' '.join(args[:3])} failed: {detail or 'unknown error'}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise ContractFailure("docker returned invalid JSON") from error


def docker_run(*args: str, input_text: str | None = None) -> str:
    completed = subprocess.run(
        ["docker", *args],
        check=False,
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        detail = redact(completed.stderr.strip())
        raise ContractFailure(f"docker {' '.join(args[:3])} failed: {detail or 'unknown error'}")
    return completed.stdout


def docker_try(*args: str) -> tuple[int, str, str]:
    completed = subprocess.run(
        ["docker", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return completed.returncode, completed.stdout, completed.stderr


def docker_missing(stderr: str, resource: str) -> bool:
    """Return true only for Docker's expected exact-resource absence diagnostics."""

    text = stderr.lower()
    if "no such object" in text:
        return True
    if resource == "container":
        return "no such container" in text or "container not found" in text
    if resource == "network":
        return "no such network" in text or bool(re.search(r"network [a-z0-9_.-]+ not found", text))
    return False


def inspect_image(image: str, expected_architecture: str | None = None) -> dict[str, Any]:
    platform_args = ["--platform", "linux/" + expected_architecture] if expected_architecture else []
    inspected = docker_json("image", "inspect", *platform_args, image)
    if not isinstance(inspected, list) or len(inspected) != 1:
        raise ContractFailure("image inspect did not resolve exactly one local image")
    value = inspected[0]
    if not value.get("Id"):
        raise ContractFailure("local image has no immutable image id")
    if expected_architecture is not None and value.get("Architecture") != expected_architecture:
        raise ContractFailure(
            f"image architecture {value.get('Architecture')!r} does not match requested "
            f"{expected_architecture!r}"
        )
    if "@sha256:" in image:
        digests = value.get("RepoDigests") or []
        if image not in digests:
            raise ContractFailure("digest-qualified image was not resolved to its requested source")
    return value


def wait_for_health(container: str, timeout: float = 90.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        code, _, _ = docker_try("exec", container, "wget", "-q", "--spider", "http://127.0.0.1:4566/_floci/health")
        if code == 0:
            return
        time.sleep(1)
    raise ContractFailure("Floci health endpoint did not become ready")


def parse_env(inspect: dict[str, Any]) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in inspect.get("Config", {}).get("Env") or []:
        if "=" in item:
            key, value = item.split("=", 1)
            result[key] = value
    return result


def exact_container_inspect(container: str) -> dict[str, Any]:
    values = docker_json("inspect", container)
    if not isinstance(values, list) or len(values) != 1:
        raise ContractFailure("container inspect did not resolve exactly one container")
    return values[0]


def assert_no_host_ports(inspect: dict[str, Any], what: str) -> None:
    bindings = inspect.get("HostConfig", {}).get("PortBindings")
    if bindings:
        raise ContractFailure(f"{what} publishes a host port")


def assert_floci_runtime(floci: str, network: str) -> dict[str, str]:
    inspect = exact_container_inspect(floci)
    assert_no_host_ports(inspect, "Floci")
    if docker_run("port", floci).strip():
        raise ContractFailure("Floci exposes a host port")
    networks = inspect.get("NetworkSettings", {}).get("Networks") or {}
    endpoint = networks.get(network) or {}
    ipam = endpoint.get("IPAMConfig") or {}
    if "169.254.170.2" not in (ipam.get("LinkLocalIPs") or []):
        raise ContractFailure("Floci was not assigned the ECS metadata link-local address")
    uid = docker_run("exec", floci, "sh", "-c", "awk '/^Uid:/ {print $2}' /proc/1/status").strip()
    if uid != "1001":
        raise ContractFailure(f"Floci listener process has uid {uid!r}, expected 1001")
    # A custom Docker context can use a different socket from /var/run/docker.sock.
    # Fail before launching tasks if the emulator and controller see different daemons.
    expected_daemon = docker_run("info", "--format", "{{.ID}}").strip()
    actual_daemon = docker_run(
        "exec", floci, "python3", "-c",
        "import http.client,json,socket; "
        "c=http.client.HTTPConnection('localhost',timeout=5); "
        "c.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); c.sock.settimeout(5); "
        "c.sock.connect('/var/run/docker.sock'); c.request('GET','/info'); "
        "r=c.getresponse(); assert r.status == 200; print(json.load(r)['ID']); c.close()",
    ).strip()
    if not expected_daemon or actual_daemon != expected_daemon:
        raise ContractFailure("Floci Docker socket and controller target different daemons")
    return {
        "controller_fingerprint": hashlib.sha256(expected_daemon.encode()).hexdigest(),
        "mounted_socket_fingerprint": hashlib.sha256(actual_daemon.encode()).hexdigest(),
    }


def task_container_inspects(
    task_ids: set[str], network: str, expected_image_id: str | None = None
) -> dict[str, dict[str, Any]]:
    listed = docker_run(
        "ps",
        "-aq",
        "--filter",
        "label=io.floci.service=ecs",
    ).splitlines()
    result: dict[str, dict[str, Any]] = {}
    link_local_ips: set[str] = set()
    for container_id in listed:
        container_id = container_id.strip()
        if not container_id:
            continue
        try:
            inspect = exact_container_inspect(container_id)
        except ContractFailure:
            continue
        labels = inspect.get("Config", {}).get("Labels") or {}
        task_id = labels.get("io.floci.resource-id")
        if task_id not in task_ids:
            continue
        if task_id in result:
            raise ContractFailure(f"multiple ECS containers found for task {task_id}")
        assert_no_host_ports(inspect, f"task {task_id}")
        actual_image = (inspect.get("ImageManifestDescriptor") or {}).get("digest") or inspect.get("Image")
        if expected_image_id is not None and actual_image != expected_image_id:
            raise ContractFailure(f"task {task_id} did not launch the requested probe image")
        endpoint = (inspect.get("NetworkSettings", {}).get("Networks") or {}).get(network) or {}
        link_local = (endpoint.get("IPAMConfig") or {}).get("LinkLocalIPs") or []
        if len(link_local) != 1 or link_local[0] == "169.254.170.2":
            raise ContractFailure("task did not receive a unique private metadata link-local address")
        if link_local[0] in link_local_ips:
            raise ContractFailure("ECS task containers reused a metadata link-local address")
        link_local_ips.add(link_local[0])
        result[task_id] = inspect
    return result


def wait_for_task_containers(
    task_ids: set[str], network: str, expected_image_id: str | None = None, timeout: float = 45.0
) -> dict[str, dict[str, Any]]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = task_container_inspects(task_ids, network, expected_image_id)
        if found.keys() == task_ids:
            return found
        time.sleep(1)
    raise ContractFailure("not all ECS task containers appeared")


def wait_for_probe_json(container: str, unknown: bool = False, timeout: float = 35.0,
                        idle_seconds: int = 0, retry_failures: bool = True,
                        resource_scope: bool = False) -> dict[str, Any]:
    command = ["python3", "/opt/fork-ecs-probe.py", "--once"]
    if idle_seconds:
        command.extend(["--idle-seconds", str(idle_seconds)])
    if unknown:
        command.append("--unknown")
    if resource_scope:
        command.append("--resource-scope")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        code, stdout, stderr = docker_try("exec", container, *command)
        if code == 0:
            for line in reversed(stdout.splitlines()):
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(value, dict):
                    return value
        if idle_seconds or not retry_failures:
            # A cached-client or generation-observation failure must not be hidden by a
            # fresh-client retry.
            break
        time.sleep(1)
    detail = redact((stderr or "").strip())
    raise ContractFailure(f"probe did not pass in container {container}: {detail or 'no JSON result'}")


def wait_for_rotated_probe(container: str, previous_key: str, timeout: float) -> dict[str, Any]:
    """Observe one natural refresh without retrying a failed positive-auth probe."""

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        remaining = max(1.0, deadline - time.monotonic())
        result = wait_for_probe_json(
            container,
            timeout=min(35.0, remaining),
            retry_failures=False,
        )
        if result.get("access_key_fingerprint") != previous_key:
            return result
        time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))
    raise ContractFailure("task credential did not rotate within the bounded refresh window")


def expiration_epoch(value: Any) -> float:
    if not isinstance(value, str) or not value:
        raise ContractFailure("credential capture omitted expiration")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ContractFailure("credential capture expiration is not ISO-8601") from error
    if parsed.tzinfo is None:
        raise ContractFailure("credential capture expiration has no timezone")
    return parsed.timestamp()


def wait_for_expiration(value: Any, maximum_wait: float = 180.0) -> float:
    """Wait for one captured generation's advertised expiry, with a bounded deadline."""

    expiry = expiration_epoch(value)
    remaining = expiry - time.time()
    if remaining < -1:
        raise ContractFailure("captured credential expired before its expiry wait")
    if remaining > maximum_wait:
        raise ContractFailure("captured credential expiry exceeded the bounded wait")
    while remaining > 0:
        time.sleep(min(30.0, remaining + 1.0))
        remaining = expiry - time.time()
    return expiry


def control(floci: str, operation: str, payload: dict[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    completed = subprocess.run(
        ["docker", "exec", "-i", floci, "python3", "/tmp/fork-ecs-control.py", operation],
        input=json.dumps(payload, separators=(",", ":")),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        detail = redact(completed.stderr.strip())
        raise ContractFailure(f"control operation {operation!r} failed: {detail or 'unknown error'}")
    for line in reversed(completed.stdout.splitlines()):
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            # Failure-safe diagnostics: never serialize the request or raw credentials.
            diagnostic = {key: value[key] for key in (
                "status", "expiration", "captured", "denied", "allowed",
                "access_key_fingerprint", "path_fingerprint",
            ) if key in value}
            print(json.dumps({"event": "control", "operation": operation,
                              "label": payload.get("label"),
                              "wall_time": time.time(), "monotonic": time.monotonic(),
                              "elapsed_seconds": time.monotonic() - started,
                              "result": diagnostic}, sort_keys=True), file=sys.stderr, flush=True)
            return value
    raise ContractFailure(f"control operation {operation!r} returned no JSON")


def copy_control_driver(floci: str) -> None:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".py", delete=False) as handle:
        handle.write(CONTROL_DRIVER)
        local_path = handle.name
    try:
        docker_run("cp", local_path, floci + ":/tmp/fork-ecs-control.py")
    finally:
        try:
            os.unlink(local_path)
        except FileNotFoundError:
            pass


def cleanup(
    floci: str,
    network: str,
    task_containers: dict[str, dict[str, Any]],
    task_ids: set[str],
    floci_created: bool,
    network_created: bool,
) -> list[str]:
    errors: list[str] = []
    # A failed RunTask/start can create a child after the last successful discovery pass.  Scan
    # the exact task IDs again during teardown so no contract-owned ECS container is left behind.
    all_task_inspects = dict(task_containers)
    if task_ids:
        listed = docker_try("ps", "-aq", "--filter", "label=io.floci.service=ecs")
        if listed[0] == 0:
            for container_id in listed[1].splitlines():
                container_id = container_id.strip()
                if not container_id:
                    continue
                inspected = docker_try("inspect", container_id)
                if inspected[0] != 0:
                    if not docker_missing(inspected[2], "container"):
                        errors.append("task container inspect: " + redact(inspected[2].strip()))
                    continue
                try:
                    value = json.loads(inspected[1])[0]
                except (json.JSONDecodeError, IndexError, TypeError):
                    errors.append("task container inspect returned invalid JSON")
                    continue
                resource_id = (value.get("Config", {}).get("Labels") or {}).get("io.floci.resource-id")
                if resource_id in task_ids:
                    all_task_inspects[resource_id] = value
        else:
            errors.append("task container listing: " + redact(listed[2].strip()))
    for task_id, inspect in all_task_inspects.items():
        container_id = inspect.get("Id") or inspect.get("ID")
        if not container_id:
            continue
        code, _, stderr = docker_try("rm", "-fv", container_id)
        if code != 0 and not docker_missing(stderr, "container"):
            errors.append("task container cleanup: " + redact(stderr.strip()))
    if floci_created:
        code, _, stderr = docker_try("rm", "-fv", floci)
        if code != 0 and not docker_missing(stderr, "container"):
            errors.append("Floci cleanup: " + redact(stderr.strip()))
    if network_created:
        code, _, stderr = docker_try("network", "rm", network)
        if code != 0 and not docker_missing(stderr, "network"):
            errors.append("network cleanup: " + redact(stderr.strip()))
    if floci_created:
        code, _, stderr = docker_try("container", "inspect", floci)
        if code == 0:
            errors.append("exact Floci container still exists")
        elif not docker_missing(stderr, "container"):
            errors.append("Floci absence inspect: " + redact(stderr.strip()))
    if network_created:
        code, _, stderr = docker_try("network", "inspect", network)
        if code == 0:
            errors.append("exact test network still exists")
        elif not docker_missing(stderr, "network"):
            errors.append("network absence inspect: " + redact(stderr.strip()))
    return errors


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diagnostic-idle-seconds", type=int, default=0,
                        help="bounded extra idle time before A renewal; diagnostic only")
    parser.add_argument("image", help="local or digest-qualified Floci image under test")
    parser.add_argument("--probe-image", required=True, help="local probe image built from Dockerfile.fork-probe")
    parser.add_argument("--network", help="unique test network name (default: generated)")
    parser.add_argument("--floci-name", help="unique Floci container name (default: generated)")
    parser.add_argument(
        "--docker-socket", default="/var/run/docker.sock",
        help="daemon-host socket path to mount into Floci (not the workstation proxy path)",
    )
    parser.add_argument(
        "--platform",
        choices=("amd64", "arm64"),
        help="Docker target platform for Floci and the probe image (default: daemon platform)",
    )
    parser.add_argument("--rotation-wait-seconds", type=int, default=61)
    parser.add_argument(
        "--expiry-wait-seconds",
        type=int,
        default=180,
        help="maximum seconds allowed for a captured credential to reach its advertised expiry",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 0 <= args.diagnostic_idle_seconds <= 90:
        raise ContractFailure("diagnostic idle must be between 0 and 90 seconds")
    if not args.docker_socket.startswith("/") or any(c in args.docker_socket for c in ",\n\r"):
        raise ContractFailure("Docker socket must be an absolute daemon-host path without commas or newlines")
    if args.rotation_wait_seconds < 61 or args.rotation_wait_seconds > 90:
        raise ContractFailure("rotation wait must be between 61 and 90 seconds")
    if args.expiry_wait_seconds < 121 or args.expiry_wait_seconds > 300:
        raise ContractFailure("expiry wait bound must be between 121 and 300 seconds")

    run_id = uuid.uuid4().hex[:10]
    network = args.network or "floci-dev1434-ecs-" + run_id
    floci = args.floci_name or "floci-dev1434-" + run_id
    role_name = "fork-ecs-role-" + run_id
    cluster_name = "fork-ecs-cluster-" + run_id
    service_names = {
        "allowed_a": "fork-ecs-allowed-a-" + run_id,
        "allowed_b": "fork-ecs-allowed-b-" + run_id,
        "forbidden": "fork-ecs-forbidden-" + run_id,
        "foreign": "fork-ecs-foreign-" + run_id,
    }
    foreign_cluster_name = "fork-ecs-foreign-cluster-" + run_id
    family = "fork-ecs-task-" + run_id
    labels = [
        "floci.fork.contract=true",
        "floci.fork.ticket=DEV-1434",
        "floci.fork.run=" + run_id,
    ]
    floci_created = False
    network_created = False
    task_containers: dict[str, dict[str, Any]] = {}
    task_ids: set[str] = set()
    task_arns: list[str] = []
    primary_error: Exception | None = None
    success_result: dict[str, Any] | None = None
    try:
        floci_image = inspect_image(args.image, args.platform)
        probe_image = inspect_image(args.probe_image, args.platform)
        docker_run("network", "create", "--driver", "bridge", *sum((["--label", label] for label in labels), []), network)
        network_created = True
        # The emulator is the sole container allowed to mount the Docker socket.  No host ports
        # are specified: all API control goes through docker exec on localhost:4566.
        create_args = ["create"]
        if args.platform:
            create_args.extend(["--platform", "linux/" + args.platform])
        create_args.extend(
            [
            "--name",
            floci,
            "--hostname",
            floci,
            "--network",
            network,
            "--network-alias",
            floci,
            "--label",
            labels[0],
            "--label",
            labels[1],
            "--label",
            labels[2],
            "--mount",
            "type=bind,src=" + args.docker_socket + ",dst=/var/run/docker.sock",
            "-e",
            "FLOCI_BASE_URL=http://" + floci + ":4566",
            "-e",
            "FLOCI_HOSTNAME=" + floci,
            "-e",
            "FLOCI_STORAGE_MODE=memory",
            "-e",
            "FLOCI_SERVICES_DOCKER_NETWORK=" + network,
            "-e",
            "FLOCI_SERVICES_ECS_DOCKER_NETWORK=" + network,
            "-e",
            "FLOCI_SERVICES_ECS_MOCK=false",
            "-e",
            "FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_ENABLED=true",
            "-e",
            "FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_PORT=80",
            "-e",
            "FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_TTL_SECONDS=120",
            "-e",
            "FLOCI_SERVICES_ECS_TASK_ROLE_CREDENTIALS_REFRESH_WINDOW_SECONDS=60",
            "-e",
            "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true",
            "-e",
            "FLOCI_DNS_CONTAINER_FALLBACK_ENABLED=false",
            args.image,
            ]
        )
        docker_run(*create_args)
        floci_created = True
        # Docker's reconnect-before-start sequence is part of the contract.  This is what makes
        # 169.254.170.2 private to the Floci container while keeping task allocations separate.
        docker_run("network", "disconnect", network, floci)
        docker_run(
            "network",
            "connect",
            "--link-local-ip",
            "169.254.170.2",
            "--alias",
            floci,
            network,
            floci,
        )
        docker_run("start", floci)
        wait_for_health(floci)
        daemon_identity = assert_floci_runtime(floci, network)
        floci_inspect = exact_container_inspect(floci)
        actual_image = (floci_inspect.get("ImageManifestDescriptor") or {}).get("digest") or floci_inspect.get("Image")
        if actual_image != floci_image["Id"]:
            raise ContractFailure("Floci container did not use the inspected image")
        copy_control_driver(floci)
        listener = control(floci, "metadata", {"path": "/v2/credentials/" + ("Z" * 48)})
        if listener.get("status") != 404:
            raise ContractFailure("private metadata listener did not reject an unknown path")

        endpoint = "http://" + floci + ":4566"
        setup = control(
            floci,
            "setup",
            {
                "role_name": role_name,
                "cluster_name": cluster_name,
                "service_names": service_names,
                "foreign_cluster_name": foreign_cluster_name,
                "family": family,
                "probe_image": args.probe_image,
                "floci_name": floci,
                "endpoint_allowlist": endpoint,
                "bucket": "fork-ecs-" + run_id,
                "object_key": "contract-object",
                "platform": args.platform,
            },
        )
        if setup.get("task_image") != args.probe_image:
            raise ContractFailure("task definition image does not match the requested probe image")
        if setup.get("ttl_seconds") != 120 or setup.get("refresh_window_seconds") != 60:
            raise ContractFailure("task credential TTL/refresh configuration was not applied")
        if args.platform:
            expected_runtime_platform = {
                "cpuArchitecture": "ARM64" if args.platform == "arm64" else "X86_64",
                "operatingSystemFamily": "LINUX",
            }
            if setup.get("runtime_platform") != expected_runtime_platform:
                raise ContractFailure("task definition runtimePlatform did not match requested platform")
        run_result = control(
            floci,
            "run",
            {
                "cluster_arn": setup["cluster_arn"],
                "task_definition_arn": setup["task_definition_arn"],
            },
        )
        task_arns = [item["task_arn"] for item in run_result["tasks"]]
        if len(set(task_arns)) != 3:
            raise ContractFailure("RunTask did not return three unique task ARNs")
        task_ids = {arn.rsplit("/", 1)[-1] for arn in task_arns}
        task_arn_by_id = {arn.rsplit("/", 1)[-1]: arn for arn in task_arns}
        task_containers = wait_for_task_containers(task_ids, network, probe_image["Id"])

        env_by_task: dict[str, dict[str, str]] = {}
        path_by_task: dict[str, str] = {}
        for task_id, inspect in task_containers.items():
            env = parse_env(inspect)
            env_by_task[task_id] = env
            path = env.get("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "")
            if not PATH_RE.fullmatch(path):
                raise ContractFailure("task did not receive a valid ECS relative credential URI")
            path_by_task[task_id] = path
            if env.get("AWS_EC2_METADATA_DISABLED") != "true":
                raise ContractFailure("task did not disable EC2 metadata credentials")
            for key in FORBIDDEN_TASK_ENV:
                if env.get(key):
                    raise ContractFailure(f"task exposes forbidden credential source {key}")
            if env.get("AWS_ENDPOINT_URL") != endpoint:
                raise ContractFailure("task AWS_ENDPOINT_URL is outside the explicit local allowlist")
        if len(set(path_by_task.values())) != 3:
            raise ContractFailure("three tasks did not receive unique opaque credential paths")

        probe_results: dict[str, dict[str, Any]] = {}
        for task_id, inspect in task_containers.items():
            probe_results[task_id] = wait_for_probe_json(inspect["Id"])
            result = probe_results[task_id]
            if result.get("credential_provider") != "container-role":
                raise ContractFailure("probe did not use boto3's container-role provider")
            if result.get("s3_denied") is not True:
                raise ContractFailure("task role S3 deny was not observed")
            if result.get("endpoint") != endpoint:
                raise ContractFailure("probe endpoint allowlist assertion did not pass")
            if result.get("credential_path_fingerprint") is None:
                raise ContractFailure("probe did not return a redacted credential-path fingerprint")
            if result["credential_path_fingerprint"] != __import__("hashlib").sha256(
                path_by_task[task_id].encode("utf-8")
            ).hexdigest()[:16]:
                raise ContractFailure("probe path fingerprint does not match the task's private path")
            if result.get("role_arn", "").count("/" + setup["role_name"] + "/") != 1:
                raise ContractFailure("probe identity is not the configured task role")
        identity_arns = {result.get("role_arn") for result in probe_results.values()}
        access_fingerprints = {result.get("access_key_fingerprint") for result in probe_results.values()}
        if len(identity_arns) != 3 or len(access_fingerprints) != 3:
            raise ContractFailure("task role identities or access keys were not unique")

        # Exercise ECS service resource scoping through the workload's default boto3 provider
        # chain before any timed credential capture. The control readback surrounds the denied
        # UpdateService so a successful-looking denial cannot hide a state mutation.
        forbidden_service = setup["services"]["forbidden"]
        forbidden_before = control(
            floci,
            "service_state",
            {"cluster_arn": setup["cluster_arn"], "service_arn": forbidden_service["arn"]},
        )
        if forbidden_before.get("desired_count") != 0:
            raise ContractFailure("forbidden ECS service did not start at desiredCount zero")
        resource_scope_probe = wait_for_probe_json(
            task_containers[min(task_containers)]["Id"],
            retry_failures=False,
            resource_scope=True,
        )
        if (resource_scope_probe.get("ecs_resource_scope") or {}).get("passed") is not True:
            raise ContractFailure("task-role ECS service resource scope contract did not pass")
        forbidden_after = control(
            floci,
            "service_state",
            {"cluster_arn": setup["cluster_arn"], "service_arn": forbidden_service["arn"]},
        )
        if forbidden_after.get("desired_count") != forbidden_before.get("desired_count"):
            raise ContractFailure("denied ECS UpdateService changed the forbidden service")

        ordered_task_ids = sorted(task_containers)
        # Diagnostic idleness precedes credential captures so the overlap assertion still tests
        # live generations rather than accidentally waiting out their advertised expiration.
        time.sleep(args.diagnostic_idle_seconds)
        initial_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[0]], "label": "initial"})
        task1_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[1]], "label": "task1"})
        task2_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[2]], "label": "task2"})
        for captured in (initial_capture, task1_capture, task2_capture):
            if captured.get("captured") is not True:
                raise ContractFailure("controller could not capture an active task credential")

        object_ref = {"bucket": setup["bucket"], "object_key": setup["object_key"]}
        active_replay = control(floci, "replay", {"label": "initial", **object_ref})
        if active_replay.get("denied") is not False or active_replay.get("allowed") is not True:
            raise ContractFailure("active task credentials could not read the allowed S3 object")

        # Task A proves enumeration denial with a fabricated path.  It never possesses task B's
        # path, so the known local bearer-replay capability is intentionally not tested as ACL
        # isolation.  The probe only records HTTP 404 and fingerprints.
        unknown_result = wait_for_probe_json(task_containers[ordered_task_ids[0]]["Id"], unknown=True)
        if unknown_result.get("unknown_metadata_status") != 404 or unknown_result.get("unknown_metadata_denied") is not True:
            raise ContractFailure("task A could enumerate an unknown metadata path")

        rotated_probe = wait_for_rotated_probe(
            task_containers[ordered_task_ids[0]]["Id"],
            initial_capture.get("access_key_fingerprint", ""),
            args.rotation_wait_seconds,
        )
        if rotated_probe.get("credential_provider") != "container-role":
            raise ContractFailure("rotated probe left the default provider chain")
        if rotated_probe.get("credential_path_fingerprint") != initial_capture.get("path_fingerprint"):
            raise ContractFailure("credential URI changed during rotation")
        if rotated_probe.get("access_key_fingerprint") == initial_capture.get("access_key_fingerprint"):
            raise ContractFailure("credential access key did not rotate inside the refresh window")
        rotated_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[0]], "label": "rotated"})
        if rotated_capture.get("access_key_fingerprint") != rotated_probe.get("access_key_fingerprint"):
            raise ContractFailure("controller and probe disagreed on the rotated access key")

        # Capture B before A stops. Its subsequent positive replay must use an already-issued
        # generation, not credentials fetched after the revocation under test.
        task1_capture = control(
            floci, "capture", {"path": path_by_task[ordered_task_ids[1]], "label": "task1"},
        )
        if task1_capture.get("captured") is not True:
            raise ContractFailure("Task B credentials were not live before Task A revocation")

        # Prove bounded overlap and stop revocation in one control process.  Both generations must
        # remain valid before their advertised expiry; the exact Task A stop must then revoke both.
        rotation_overlap = control(
            floci,
            "overlap",
            {
                "cluster_arn": setup["cluster_arn"],
                "task_arn": task_arn_by_id[ordered_task_ids[0]],
                "path": path_by_task[ordered_task_ids[0]],
                "bucket": setup["bucket"],
                "object_key": setup["object_key"],
                "initial_label": "initial",
                "rotated_label": "rotated",
                "minimum_remaining_seconds": 10,
            },
        )
        if rotation_overlap.get("overlap") is not True:
            raise ContractFailure("generation overlap contract did not pass")

        # Task B's pre-stop capture remains untouched so its positive read proves that stopping
        # Task A is task-scoped rather than a transport-wide or role-wide deny.
        live_b_replay = control(floci, "replay", {"label": "task1", **object_ref})
        if live_b_replay.get("denied") is not False or live_b_replay.get("allowed") is not True:
            raise ContractFailure("revoking Task A denied a still-live Task B credential")

        # Task C stays idle past its credential TTL, but its task authorization must survive.
        # Reject the captured expired key before any SDK request can renew the credential.
        idle_task = task_containers[ordered_task_ids[2]]
        idle_started_at = exact_container_inspect(idle_task["Id"]).get("State", {}).get("StartedAt")
        # ARM emulation can spend most of a lease on the preceding A-rotation checks. Refresh the
        # capture itself immediately before the bounded expiry proof, then use only that captured
        # generation for the positive read and post-expiry replay.
        task2_capture = control(
            floci,
            "capture",
            {"path": path_by_task[ordered_task_ids[2]], "label": "task2"},
        )
        if task2_capture.get("captured") is not True:
            raise ContractFailure("controller could not capture the current Task C credential")
        pre_expiry_replay = control(floci, "replay", {"label": "task2", **object_ref})
        if pre_expiry_replay.get("denied") is not False or pre_expiry_replay.get("allowed") is not True:
            raise ContractFailure("captured Task C credentials were not usable before expiration")
        wait_for_expiration(task2_capture.get("expiration"), maximum_wait=args.expiry_wait_seconds)
        expired_replay = control(floci, "replay", {"label": "task2", **object_ref})
        if expired_replay.get("denied") is not True:
            raise ContractFailure("expired task credentials were still accepted")
        idle_state = exact_container_inspect(idle_task["Id"]).get("State", {})
        if idle_state.get("Running") is not True:
            raise ContractFailure("idle task stopped before credential continuity check")
        if not idle_started_at or idle_state.get("StartedAt") != idle_started_at:
            raise ContractFailure("idle task restarted during credential continuity check")
        resumed_probe = wait_for_probe_json(idle_task["Id"])
        if resumed_probe.get("credential_provider") != "container-role":
            raise ContractFailure("idle task left the default credential provider")
        if resumed_probe.get("credential_path_fingerprint") != task2_capture.get("path_fingerprint"):
            raise ContractFailure("idle task credential URI changed")
        if resumed_probe.get("access_key_fingerprint") == task2_capture.get("access_key_fingerprint"):
            raise ContractFailure("idle task reused its expired credential")
        resumed_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[2]], "label": "resumed"})
        if resumed_capture.get("access_key_fingerprint") != resumed_probe.get("access_key_fingerprint"):
            raise ContractFailure("idle task and controller disagreed on renewed credentials")

        cached_probe = wait_for_probe_json(idle_task["Id"], timeout=160, idle_seconds=125)
        if cached_probe.get("cached_client_continuity") is not True:
            raise ContractFailure("long-lived SDK client did not prove credential continuity")
        if cached_probe.get("credential_path_fingerprint") != task2_capture.get("path_fingerprint"):
            raise ContractFailure("cached SDK client credential URI changed")
        cached_state = exact_container_inspect(idle_task["Id"]).get("State", {})
        if cached_state.get("Running") is not True or cached_state.get("StartedAt") != idle_started_at:
            raise ContractFailure("cached SDK task stopped or restarted during idle")
        resumed_capture = control(floci, "capture", {"path": path_by_task[ordered_task_ids[2]], "label": "resumed"})
        if resumed_capture.get("access_key_fingerprint") != cached_probe.get("access_key_fingerprint"):
            raise ContractFailure("cached SDK and controller disagreed on renewed credentials")

        control(
            floci,
            "stop",
            {
                "cluster_arn": setup["cluster_arn"],
                "task_arns": [task_arn_by_id[task_id] for task_id in ordered_task_ids[1:]],
            },
        )
        for task_id, label in (
            (ordered_task_ids[0], "rotated"),
            (ordered_task_ids[1], "task1"),
            (ordered_task_ids[2], "resumed"),
        ):
            stopped_metadata = control(floci, "metadata", {"path": path_by_task[task_id]})
            if stopped_metadata.get("status") != 404:
                raise ContractFailure("stopped task credential URI remained live")
            stopped_replay = control(floci, "replay", {"label": label, **object_ref})
            if stopped_replay.get("denied") is not True:
                raise ContractFailure("stopped task credentials were still accepted")
        control(floci, "cleanup", setup)
        success_result = {
            "status": "passed",
            "contract": "DEV-1434 ECS task-role credentials",
            "task_count": 3,
            "credential_provider": "container-role",
            "rotation": "stable URI, new key, TTL120/refresh60",
            "authorization": "allowed object read; list-buckets denied",
            "observed_tasks": probe_results,
            "ecs_service_scope": {
                "probe": resource_scope_probe,
                "forbidden_before": forbidden_before,
                "forbidden_after": forbidden_after,
            },
            "rotated_task": rotated_probe,
            "rotation_overlap": rotation_overlap,
            "unaffected_live_task": live_b_replay,
            "pre_expiry_replay": pre_expiry_replay,
            "expiry_denial": expired_replay,
            "idle_task_continuity": resumed_probe,
            "cached_client_continuity": cached_probe,
            "image_platform": args.platform,
            "image_manifest": floci_image["Id"],
            "docker_daemon_identity": daemon_identity,
            "isolation": "unknown path denied; cross-task bearer replay intentionally out of scope",
            "cleanup": "Floci state and exact Docker resources removed",
        }
    except Exception as error:
        primary_error = error
    finally:
        # Inspect only allowlisted state, before cleanup destroys failure evidence.
        for task_id, task in task_containers.items():
            try:
                state = exact_container_inspect(task["Id"]).get("State", {})
                print(json.dumps({"event": "task_final_state", "task_id": task_id,
                                  "wall_time": time.time(), "monotonic": time.monotonic(),
                                  "state": {key: state.get(key) for key in (
                                      "Status", "Running", "ExitCode", "OOMKilled", "StartedAt", "FinishedAt"
                                  )}}, sort_keys=True), file=sys.stderr, flush=True)
            except Exception:
                print(json.dumps({"event": "task_final_state_unavailable", "task_id": task_id}),
                      file=sys.stderr, flush=True)
        cleanup_errors = cleanup(floci, network, task_containers, task_ids, floci_created, network_created)
        if cleanup_errors:
            if primary_error is None:
                primary_error = ContractFailure("cleanup failed: " + "; ".join(cleanup_errors))
            else:
                print("fork ECS cleanup incomplete: " + "; ".join(cleanup_errors), file=sys.stderr)
    if primary_error is not None:
        raise primary_error
    if success_result is None:
        raise ContractFailure("contract did not produce a success result")
    print(json.dumps(success_result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        # Keep Docker/botocore diagnostics useful without ever echoing a bearer URI or key.
        print(f"fork ECS contract failed: {redact(str(error))}", file=sys.stderr)
        sys.exit(1)
