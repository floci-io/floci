"""Opt-in stateful replay. Import and normal pytest execution never contact AWS."""

import argparse
from contextlib import contextmanager, ExitStack
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit
import uuid

import boto3
import botocore.session
from botocore.config import Config

FIXTURE = Path(__file__).resolve().parent / "fixtures/metric-filter-publishing-aws.json"
OWNER_TAG = "floci-metric-replay"


class ReplayError(Exception):
    """Only fixed, sanitized diagnostics belong in this exception."""


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Invalid replay arguments; use --help. No replay was started.\n")


def parse_args(argv=None):
    parser = SafeParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--aws", action="store_true", help="Write to real commercial-partition AWS")
    mode.add_argument("--endpoint", help="Explicit loopback Floci endpoint, with dummy credentials")
    parser.add_argument("--profile")
    parser.add_argument("--region")
    parser.add_argument("--ack-live-writes", choices=["I_ACCEPT_AWS_WRITES"])
    parser.add_argument("--timeout", type=float, default=240, help="Maximum observation seconds")
    parser.add_argument("--stable-seconds", type=float, default=30, help="Complete stable observation window")
    parser.add_argument("--poll-interval", type=float, default=5)
    args = parser.parse_args(argv)
    if args.aws:
        if not args.profile or not args.region or not args.ack_live_writes:
            parser.error("AWS requires explicit profile, region and acknowledgement")
        if args.region not in botocore.session.get_session().get_available_regions("logs", partition_name="aws"):
            parser.error("Only known commercial AWS regions are supported")
        if args.stable_seconds < 30 or args.timeout < 120:
            parser.error("AWS observations require at least 120 seconds and a 30-second stable window")
    else:
        try:
            endpoint = urlsplit(args.endpoint)
        except ValueError:
            parser.error("Invalid local endpoint")
        if (endpoint.scheme not in ("http", "https")
                or endpoint.hostname not in ("localhost", "127.0.0.1", "::1")
                or endpoint.username or endpoint.password or endpoint.path not in ("", "/")
                or endpoint.query or endpoint.fragment or args.profile or args.ack_live_writes):
            parser.error("Local mode requires a credential-free loopback endpoint")
        args.region = args.region or "eu-west-1"
    if not (0 < args.poll_interval <= args.stable_seconds < args.timeout <= 900):
        parser.error("Invalid bounded observation window")
    return args


def clients(args, session=None):
    session = session or (boto3.Session(profile_name=args.profile, region_name=args.region)
                          if args.aws else boto3.Session(region_name=args.region))
    config = Config(ignore_configured_endpoint_urls=True, connect_timeout=5, read_timeout=15,
                    retries={"max_attempts": 2, "mode": "standard"})
    result = []
    for service, endpoint_prefix in (("logs", "logs"), ("cloudwatch", "monitoring"), ("sts", "sts")):
        endpoint = f"https://{endpoint_prefix}.{args.region}.amazonaws.com" if args.aws else args.endpoint
        credentials = {} if args.aws else {
            "aws_access_key_id": "test", "aws_secret_access_key": "test", "aws_session_token": ""}
        result.append(session.client(service, region_name=args.region, endpoint_url=endpoint,
                                     config=config, **credentials))
    return tuple(result)


@contextmanager
def owned_group(logs, group, token):
    # A rejected create must never cause deletion of an existing resource.
    logs.create_log_group(logGroupName=group, tags={OWNER_TAG: token})
    try:
        if logs.list_tags_log_group(logGroupName=group).get("tags", {}).get(OWNER_TAG) != token:
            raise ReplayError("created group ownership could not be verified")
        logs.create_log_stream(logGroupName=group, logStreamName="probe")
        yield
    finally:
        try:
            if logs.list_tags_log_group(logGroupName=group).get("tags", {}).get(OWNER_TAG) != token:
                raise ReplayError("cleanup refused: group ownership could not be verified")
            logs.delete_log_group(logGroupName=group)
        except Exception:
            raise ReplayError("cleanup failed or refused; inspect the owned run's log group") from None


def await_stable(read, timeout, stable_seconds, interval, monotonic=None, sleep=None):
    monotonic, sleep = monotonic or time.monotonic, sleep or time.sleep
    deadline = monotonic() + timeout
    stable_since = None
    while True:
        complete = read()
        now = monotonic()
        if now > deadline:
            raise ReplayError("observations did not converge within the bounded window")
        stable_since = (now if stable_since is None else stable_since) if complete else None
        if stable_since is not None and now - stable_since >= stable_seconds:
            return
        if now >= deadline:
            raise ReplayError("observations did not converge within the bounded window")
        sleep(min(interval, deadline - now))


def safe_error(error):
    return str(error) if isinstance(error, ReplayError) else type(error).__name__


def scenarios(fixture, start, account, region):
    cases = []

    def add(name, definition, batches, expected, system_fields=None):
        cases.append({"name": name, "minute": start + timedelta(minutes=len(cases)),
                      "definition": definition, "batches": batches,
                      "expected": expected, "system_fields": system_fields or []})

    defaults = fixture["metricDefaults"]
    for i, scenario in enumerate(defaults["cases"]):
        add(f"default-{i}", defaults, scenario["batches"], [{"dimensions": {}, **scenario["expected"]}])
    extraction = fixture["extraction"]
    for i, scenario in enumerate(extraction["cases"]):
        add(f"extraction-{i}", extraction, [[json.dumps(scenario["message"])]],
            [{"dimensions": {}, **scenario["expected"]}])
    ordinary = fixture["ordinaryDimensions"]
    add("dimensions", ordinary, [[json.dumps(m) for m in ordinary["messages"]]],
        ordinary["expectedSeries"] + [{"datapoints": [], **s} for s in ordinary["absentSeries"]])
    system = fixture["systemDimensions"]
    for i, scenario in enumerate(system["cases"]):
        expected = []
        for series in scenario["expectedSeries"]:
            dimensions = {k: account if k == "@aws.account" else region for k in series["dimensions"]}
            expected.append({**series, "dimensions": dimensions})
        add(f"system-{i}", system, [system["messages"]], expected, scenario["emitSystemFieldDimensions"])
    add("quiet", defaults, [["INFO", "INFO", "INFO"]], [{"dimensions": {}, "Sum": 21, "SampleCount": 3}])
    return cases


def observe(cw, namespace, cases):
    complete = True
    observations = []
    for case in cases:
        for expected in case["expected"]:
            dimensions = expected["dimensions"]
            stats = [name for name in ("Sum", "SampleCount", "Minimum", "Maximum") if name in expected]
            points = cw.get_metric_statistics(Namespace=namespace, MetricName=case["name"],
                Dimensions=[{"Name": k, "Value": v} for k, v in dimensions.items()],
                StartTime=case["minute"], EndTime=case["minute"] + timedelta(minutes=1),
                Period=60, Statistics=stats or ["Sum", "SampleCount"])["Datapoints"]
            present = "Sum" in expected
            matches = len(points) == (1 if present else 0)
            if matches and present:
                matches = (points[0]["Timestamp"] == case["minute"]
                           and all(points[0].get(stat) == expected[stat] for stat in stats))
            complete = complete and matches
            observations.append({"case": case["name"], "timestamp": case["minute"].isoformat(),
                "dimensions": {k: "<CALLER_ACCOUNT>" if k == "@aws.account" else v for k, v in dimensions.items()},
                "datapoints": [{"timestamp": p["Timestamp"].isoformat(),
                               **{s: p[s] for s in ("Sum", "SampleCount", "Minimum", "Maximum") if s in p}}
                              for p in points]})
    return complete, observations


def main(argv=None):
    args = parse_args(argv)
    token = uuid.uuid4().hex
    group = "/floci-metric-replay/" + token
    namespace = "FlociMetricReplay/" + token
    print(json.dumps({"mode": "AWS LIVE WRITES" if args.aws else "local Floci only",
                      "group": group, "namespace": namespace,
                      "warning": "Metric series cannot be deleted. They remain until the service ages them out."}))
    try:
        with ExitStack() as stack:
            logs, cw, sts = clients(args)
            for client in (logs, cw, sts):
                stack.callback(client.close)
            account = sts.get_caller_identity()["Account"]
            start = datetime.now(timezone.utc).replace(second=0, microsecond=0) - timedelta(minutes=40)
            cases = scenarios(json.loads(FIXTURE.read_text()), start, account, args.region)
            observations = []
            with owned_group(logs, group, token):
                for case in cases:
                    definition = case["definition"]
                    transformation = {"metricNamespace": namespace, "metricName": case["name"],
                                      "metricValue": definition["metricValue"], "unit": "Count"}
                    for field in ("defaultValue", "dimensions"):
                        if field in definition:
                            transformation[field] = definition[field]
                    request = {"logGroupName": group, "filterName": "probe",
                               "filterPattern": definition["filterPattern"],
                               "metricTransformations": [transformation]}
                    if case["system_fields"]:
                        request["emitSystemFieldDimensions"] = case["system_fields"]
                    logs.put_metric_filter(**request)
                    for batch in case["batches"]:
                        response = logs.put_log_events(logGroupName=group, logStreamName="probe",
                            logEvents=[{"timestamp": int(case["minute"].timestamp() * 1000) + i, "message": message}
                                       for i, message in enumerate(batch)])
                        if response.get("rejectedLogEventsInfo"):
                            raise ReplayError("replay log events were rejected")

                checks = cases + [{"name": "quiet", "minute": cases[-1]["minute"] + timedelta(minutes=1),
                                   "expected": [{"dimensions": {}, "datapoints": []}]}]

                def read():
                    nonlocal observations
                    complete, observations = observe(cw, namespace, checks)
                    return complete

                try:
                    await_stable(read, args.timeout, args.stable_seconds, args.poll_interval)
                finally:
                    print(json.dumps({"observations": observations}))
            print(json.dumps({"result": "converged", "cleanup": "owned log group deleted",
                              "qualification": "Local results are not live AWS verification."
                              if not args.aws else "Only these replayed scenarios were observed."}))
        return 0
    except Exception as error:
        print(json.dumps({"result": "failed", "error": safe_error(error),
                          "cleanup_hint": group}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
