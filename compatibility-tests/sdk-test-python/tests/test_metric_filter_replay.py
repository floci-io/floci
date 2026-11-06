"""Offline checks only: no AWS clients or sockets are opened by these tests."""
import importlib.util
from pathlib import Path
from datetime import datetime, timedelta, timezone
import json
import socket
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

SPEC = importlib.util.spec_from_file_location(
    "metric_filter_replay", Path(__file__).resolve().parent / "metric_filter_replay.py")
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)

@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def reject(*args, **kwargs):
        pytest.fail("offline replay checks must not open a network connection")
    monkeypatch.setattr(socket.socket, "connect", reject)


@pytest.fixture
def orchestration(monkeypatch):
    logs, cw, sts = Mock(), Mock(), Mock()
    state = SimpleNamespace(metric=None, ingestions=[], observations=0, now=0)
    sts.get_caller_identity.return_value = {"Account": "123456789012"}
    logs.list_tags_log_group.side_effect = lambda **kwargs: {"tags": logs.create_log_group.call_args.kwargs["tags"]}
    def put_filter(**request):
        state.metric = request["metricTransformations"][0]["metricName"]
    def put_events(**request):
        state.ingestions.append((state.metric, request["logEvents"]))
        return {}
    def sleep(seconds):
        state.now += seconds
    logs.put_metric_filter.side_effect = put_filter
    logs.put_log_events.side_effect = put_events
    monkeypatch.setattr(replay, "clients", lambda args: (logs, cw, sts))
    monkeypatch.setattr(replay.time, "monotonic", lambda: state.now)
    monkeypatch.setattr(replay.time, "sleep", sleep)
    return state


def test_fixture_is_packaged_beside_the_module_not_in_a_repository_ancestor():
    expected = Path(__file__).resolve().parent / "fixtures/metric-filter-publishing-aws.json"
    assert replay.FIXTURE == expected
    assert replay.FIXTURE.is_file()


def test_quiet_ingestion_is_last_through_successful_stable_observation(monkeypatch, orchestration):
    state = orchestration
    def observe(*args):
        state.observations += 1
        assert state.ingestions[-1][0] == "quiet", "later traffic could flush a broken quiet publisher"
        assert [e["message"] for e in state.ingestions[-1][1]] == ["INFO", "INFO", "INFO"]
        assert sum(metric == "quiet" for metric, _ in state.ingestions) == 1
        return state.observations > 1, []
    monkeypatch.setattr(replay, "observe", observe)
    assert replay.main(["--endpoint", "http://127.0.0.1:4566", "--timeout", "10",
                        "--stable-seconds", "2", "--poll-interval", "1"]) == 0
    assert state.observations >= 4
    assert state.ingestions[-1][0] == "quiet"


def test_idle_window_is_distinct_unwritten_and_checked_through_convergence(monkeypatch, orchestration):
    state = orchestration
    def observe(cw, namespace, cases):
        state.observations += 1
        quiet = [case for case in cases if case["name"] == "quiet"]
        assert len(quiet) == 2, "check the positive quiet minute and a separate empty minute"
        populated, idle = sorted(quiet, key=lambda case: case["minute"])
        assert idle["minute"] == populated["minute"] + timedelta(minutes=1)
        assert populated["expected"][0]["Sum"] == 21
        assert idle["expected"] == [{"dimensions": {}, "datapoints": []}]
        start = int(idle["minute"].timestamp() * 1000)
        assert all(not start <= event["timestamp"] < start + 60_000
                   for _, events in state.ingestions for event in events)
        return state.observations > 1, []
    monkeypatch.setattr(replay, "observe", observe)
    assert replay.main(["--endpoint", "http://127.0.0.1:4566", "--timeout", "10",
                        "--stable-seconds", "2", "--poll-interval", "1"]) == 0
    assert state.observations >= 4


@pytest.mark.parametrize("args", [
    [], ["--aws"], ["--aws", "--profile", "synthetic", "--region", "eu-west-1"],
    ["--aws", "--ack-live-writes", "I_ACCEPT_AWS_WRITES", "--region", "eu-west-1"],
    ["--aws", "--ack-live-writes", "I_ACCEPT_AWS_WRITES", "--profile", "synthetic"],
    ["--endpoint", "https://logs.eu-west-1.amazonaws.com"],
    ["--endpoint", "http://127.0.0.1:4566@evil.example"],
    ["--endpoint", "http://localhost:4566", "--profile", "synthetic"],
    ["--endpoint", "http://127.0.0.1:4566/path"],
    ["--endpoint", "http://[malformed-private-endpoint"],
    # GovCloud and China are real botocore Regions but not the commercial partition the endpoints pin.
    ["--aws", "--profile", "synthetic", "--region", "us-gov-west-1", "--ack-live-writes", "I_ACCEPT_AWS_WRITES"],
    ["--aws", "--profile", "synthetic", "--region", "cn-north-1", "--ack-live-writes", "I_ACCEPT_AWS_WRITES"],
])
def test_rejects_unguarded_or_remote_writes_before_creating_clients(args):
    with pytest.raises(SystemExit):
        replay.parse_args(args)


def test_live_clients_pin_official_endpoints_despite_environment(monkeypatch):
    monkeypatch.setenv("AWS_ENDPOINT_URL", "https://untrusted.invalid")
    monkeypatch.setenv("AWS_ENDPOINT_URL_CLOUDWATCH_LOGS", "https://untrusted.invalid")
    args = replay.parse_args(["--aws", "--profile", "synthetic", "--region", "eu-west-1",
                              "--ack-live-writes", "I_ACCEPT_AWS_WRITES"])
    session = Mock()
    replay.clients(args, session)
    calls = {call.args[0]: call.kwargs for call in session.client.call_args_list}
    assert calls["logs"]["endpoint_url"] == "https://logs.eu-west-1.amazonaws.com"
    assert calls["cloudwatch"]["endpoint_url"] == "https://monitoring.eu-west-1.amazonaws.com"
    assert calls["sts"]["endpoint_url"] == "https://sts.eu-west-1.amazonaws.com"
    assert all(c["config"].ignore_configured_endpoint_urls for c in calls.values())


def test_local_clients_use_only_explicit_loopback_and_dummy_credentials():
    args = replay.parse_args(["--endpoint", "http://127.0.0.1:18084"])
    session = Mock()
    replay.clients(args, session)
    for call in session.client.call_args_list:
        assert call.kwargs["endpoint_url"] == "http://127.0.0.1:18084"
        assert call.kwargs["aws_access_key_id"] == "test"
        assert call.kwargs["aws_secret_access_key"] == "test"
        assert call.kwargs["aws_session_token"] == ""


def test_owned_group_is_verified_again_before_cleanup_even_when_body_fails():
    logs = Mock()
    logs.list_tags_log_group.return_value = {"tags": {"floci-metric-replay": "token"}}
    with pytest.raises(RuntimeError, match="body"):
        with replay.owned_group(logs, "/owned", "token"):
            raise RuntimeError("body")
    assert logs.list_tags_log_group.call_count == 2
    logs.delete_log_group.assert_called_once_with(logGroupName="/owned")


def test_cleanup_does_not_delete_a_group_whose_ownership_changed():
    logs = Mock()
    logs.list_tags_log_group.side_effect = [
        {"tags": {"floci-metric-replay": "token"}}, {"tags": {"floci-metric-replay": "someone-else"}}]
    with pytest.raises(replay.ReplayError, match="cleanup"):
        with replay.owned_group(logs, "/owned", "token"):
            pass
    logs.delete_log_group.assert_not_called()


def test_failed_create_never_deletes_an_existing_group():
    logs = Mock()
    logs.create_log_group.side_effect = RuntimeError("private endpoint and credentials")
    with pytest.raises(RuntimeError, match="private endpoint"):
        with replay.owned_group(logs, "/owned", "token"):
            pytest.fail("must not start replay")
    logs.delete_log_group.assert_not_called()


def test_cleanup_failure_is_visible_and_does_not_leak_service_exception():
    logs = Mock()
    logs.list_tags_log_group.return_value = {"tags": {"floci-metric-replay": "token"}}
    logs.delete_log_group.side_effect = RuntimeError("private account credentials")
    with pytest.raises(replay.ReplayError, match="cleanup failed") as error:
        with replay.owned_group(logs, "/owned", "token"):
            raise ValueError("private profile")
    assert "private" not in str(error.value)


def test_polling_requires_positive_controls_then_a_stable_complete_window():
    clock = SimpleNamespace(now=0)
    def sleep(seconds):
        clock.now += seconds
    observations = iter([False, False, True, False, True, True, True])
    reads = []
    def read():
        reads.append(clock.now)
        return next(observations)
    replay.await_stable(read, timeout=20, stable_seconds=2, interval=1,
                        monotonic=lambda: clock.now, sleep=sleep)
    assert reads == [0, 1, 2, 3, 4, 5, 6]


def test_never_converging_observations_fail_at_a_bounded_deadline():
    clock = SimpleNamespace(now=0)
    def sleep(seconds):
        clock.now += seconds
    with pytest.raises(replay.ReplayError, match="converge"):
        replay.await_stable(lambda: False, timeout=3, stable_seconds=2, interval=1,
                            monotonic=lambda: clock.now, sleep=sleep)
    assert clock.now == 3


def test_slow_final_read_cannot_succeed_after_the_deadline():
    clock = SimpleNamespace(now=0)
    def sleep(seconds):
        clock.now += seconds
    def read():
        if clock.now:
            clock.now += 3
        return True
    with pytest.raises(replay.ReplayError, match="converge"):
        replay.await_stable(read, timeout=3, stable_seconds=2, interval=1,
                            monotonic=lambda: clock.now, sleep=sleep)


def test_invalid_argument_output_never_echoes_private_values(capsys):
    with pytest.raises(SystemExit):
        replay.parse_args(["--unknown", "private-profile-name"])
    assert "private-profile-name" not in capsys.readouterr().err


def test_error_output_is_sanitized():
    assert replay.safe_error(RuntimeError("profile secret account 123456789012")) == "RuntimeError"
    assert replay.safe_error(replay.ReplayError("cleanup failed")) == "cleanup failed"


def test_scenarios_use_immutable_recorded_values_and_distinct_event_minutes():
    fixture = json.loads(replay.FIXTURE.read_text())
    before = json.dumps(fixture)
    cases = replay.scenarios(fixture, datetime(2026, 9, 16, tzinfo=timezone.utc), "123456789012", "eu-west-1")
    by_name = {case["name"]: case for case in cases}
    assert len(cases) == 12
    assert len({case["minute"] for case in cases}) == 12
    assert [by_name[name]["expected"][0]["Sum"] for name in ("default-0", "default-1", "default-2", "quiet")] == [13, 10, 10, 21]
    assert by_name["extraction-0"]["expected"][0]["Sum"] == 7
    assert by_name["extraction-1"]["expected"][0]["Sum"] == 7
    assert by_name["extraction-2"]["expected"][0] == {"dimensions": {}, "datapoints": []}
    assert by_name["extraction-3"]["expected"][0]["Sum"] == 3
    assert len(by_name["dimensions"]["expected"]) == 4
    assert json.dumps(fixture) == before


def test_observation_rejects_wrong_timestamps_partial_counts_and_sanitizes_account():
    minute = datetime(2026, 9, 16, tzinfo=timezone.utc)
    case = {"name": "system", "minute": minute, "expected": [
        {"dimensions": {"@aws.account": "123456789012"}, "Sum": 3, "SampleCount": 1}]}
    cw = Mock()
    point = {"Timestamp": minute, "Sum": 3.0, "SampleCount": 1.0, "Unit": "Count"}
    cw.get_metric_statistics.return_value = {"Datapoints": [point]}
    complete, observations = replay.observe(cw, "namespace", [case])
    assert complete
    assert "123456789012" not in json.dumps(observations)
    assert "<CALLER_ACCOUNT>" in json.dumps(observations)
    point["SampleCount"] = 2
    assert not replay.observe(cw, "namespace", [case])[0]
    point["SampleCount"] = 1
    point["Timestamp"] = datetime(2026, 9, 16, 0, 1, tzinfo=timezone.utc)
    assert not replay.observe(cw, "namespace", [case])[0]
