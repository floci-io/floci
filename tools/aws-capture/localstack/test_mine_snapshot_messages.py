"""Tests for mine_snapshot_messages: the two readings this tool makes.

What a snapshot carries is plain JSON, so the parts worth testing are the two places this could
quietly answer the wrong thing: which marker a test carries, where a marker decides whether a
recording is AWS's answer or an emulator's, and which paths inside a recording are an error answer.
A marker read line by line misses a parametrized test, whose `@pytest.mark.parametrize` sits
between the marker and the `def` and spans a line per case, so that case is pinned here.

Run with: pytest tools/aws-capture/localstack -q  (or: make snapshot-mine-test)
"""
from __future__ import annotations

import json

import mine_snapshot_messages as m

SOURCE = '''
import pytest
from localstack.testing.pytest import markers


class TestOne:
    @markers.aws.validated
    def test_plain(self, aws_client, snapshot):
        pass

    @markers.aws.validated
    @pytest.mark.parametrize(
        "principal",
        [
            "one.amazonaws.com",
            "two.amazonaws.com",
        ],
    )
    def test_parametrized(self, principal):
        pass

    @markers.aws.only_localstack
    def test_emulated(self):
        pass

    def test_unmarked(self):
        pass


@markers.aws.needs_fixing
class TestTwo:
    def test_inherits(self):
        pass

    @markers.aws.validated
    def test_overrides(self):
        pass

    def test_plain(self):
        pass


class TestThree:
    def test_plain(self):
        pass


@markers.aws.validated
def test_module_level(aws_client):
    pass
'''


def snapshot_entry(content, recorded="06-03-2025, 12:24:44"):
    return {"recorded-date": recorded, "recorded-content": content}


def refusal(code, message, status=400):
    return {"Error": {"Code": code, "Message": message},
            "ResponseMetadata": {"HTTPStatusCode": status}}


def test_a_marker_is_read_off_the_decorators_and_not_the_lines():
    found = m.markers(SOURCE)

    assert found["TestOne::test_plain"] == "validated"
    # The parametrize between the marker and the def spans five lines, which a line-by-line read
    # takes for the end of the decorators.
    assert found["TestOne::test_parametrized"] == "validated"
    assert found["TestOne::test_emulated"] == "only_localstack"


def test_a_test_with_no_marker_is_absent_rather_than_unmarked():
    assert "TestOne::test_unmarked" not in m.markers(SOURCE)


def test_a_test_at_module_level_is_keyed_by_its_own_name():
    assert m.markers(SOURCE)["test_module_level"] == "validated"


def test_a_name_two_classes_share_keeps_each_class_marker_to_itself():
    # LocalStack carries 20 test names defined in more than one class. Keyed by the bare name, one
    # class's validated marker would answer for another class's test, and an unclassified
    # recording would read as AWS evidence, which is the one thing this must not do.
    found = m.markers(SOURCE)
    assert found["TestOne::test_plain"] == "validated"
    assert found["TestTwo::test_plain"] == "needs_fixing"
    assert "TestThree::test_plain" not in found

    snapshot = {"tests/aws/services/iam/test_iam.py::TestThree::test_plain": snapshot_entry(
        {"refused": refusal("InvalidInput", "unclassified")})}

    assert m.recordings("iam", snapshot, found, [], False) == []
    assert [one.marker for one in m.recordings("iam", snapshot, found, [], True)] == ["unknown"]


def test_a_class_marker_carries_to_its_tests_and_a_test_overrides_it():
    found = m.markers(SOURCE)

    assert found["TestTwo::test_inherits"] == "needs_fixing"
    assert found["TestTwo::test_overrides"] == "validated"


def test_an_error_answer_carries_its_code_status_and_message():
    snapshot = {"tests/aws/services/iam/test_iam.py::TestOne::test_plain": snapshot_entry(
        {"refused": refusal("NoSuchEntity", "The user with name bob cannot be found.", 404)})}

    found = m.recordings("iam", snapshot, m.markers(SOURCE), [], False)

    assert len(found) == 1
    assert (found[0].service, found[0].code, found[0].status) == ("iam", "NoSuchEntity", "404")
    assert found[0].message == "The user with name bob cannot be found."
    assert found[0].path == ".refused"
    assert found[0].recorded == "06-03-2025, 12:24:44"


def test_an_error_inside_a_list_keeps_its_message_and_status():
    # The path is a label, not a lookup: an answer read back by walking a dotted path loses the
    # message and the status as soon as a list index stands between it and the root.
    snapshot = {"tests/aws/services/iam/test_iam.py::TestOne::test_plain": snapshot_entry(
        {"batch": {"responses": [refusal("ValidationError", "nested", 400)]},
         "key.with.dots": refusal("NoSuchEntity", "dotted", 404)})}

    found = m.recordings("iam", snapshot, m.markers(SOURCE), [], False)

    assert [(one.path, one.code, one.status, one.message) for one in found] == [
        (".batch.responses[0]", "ValidationError", "400", "nested"),
        (".key.with.dots", "NoSuchEntity", "404", "dotted")]


def test_a_recording_from_a_test_that_never_ran_against_aws_is_left_out():
    snapshot = {"tests/aws/services/iam/test_iam.py::TestOne::test_emulated": snapshot_entry(
        {"refused": refusal("InvalidInput", "emulated")})}

    assert m.recordings("iam", snapshot, m.markers(SOURCE), [], False) == []

    kept = m.recordings("iam", snapshot, m.markers(SOURCE), [], True)
    assert [(one.marker, one.code) for one in kept] == [("only_localstack", "InvalidInput")]


def test_a_test_this_tool_cannot_classify_reads_as_unknown():
    snapshot = {"tests/aws/services/iam/test_iam.py::TestOne::test_unmarked": snapshot_entry(
        {"refused": refusal("InvalidInput", "unclassified")})}

    kept = m.recordings("iam", snapshot, m.markers(SOURCE), [], True)

    assert [one.marker for one in kept] == ["unknown"]


def test_grep_finds_a_string_that_is_no_error_message():
    snapshot = {"tests/aws/services/iam/test_iam.py::TestOne::test_plain": snapshot_entry(
        {"policy": {"Document": "1 validation error detected: Value at 'x'"},
         "refused": refusal("ValidationError", "1 validation error detected: Value at 'y'")})}

    found = m.recordings("iam", snapshot, m.markers(SOURCE), ["validation error detected"], False)

    # In the order the recording lists them, and the error message is reported once, as the answer
    # it is, rather than again as a text match of its own.
    assert [(one.path, one.code) for one in found] == [
        (".policy.Document", None), (".refused", "ValidationError")]


def test_a_snapshot_with_no_test_beside_it_is_a_note_rather_than_a_silent_skip(tmp_path):
    snapshot = tmp_path / "test_iam.snapshot.json"
    snapshot.write_text(json.dumps(
        {"tests/aws/services/iam/test_iam.py::TestOne::test_plain": snapshot_entry(
            {"refused": refusal("InvalidInput", "orphan")})}), encoding="utf-8")

    found, notes = m.mine([snapshot], [], False)

    assert found == []
    assert len(notes) == 1 and "no test file beside it" in notes[0]


def test_every_service_that_carries_a_snapshot_is_read_for_all(tmp_path):
    for service in ("iam", "cloudfront", "notests"):
        (tmp_path / m.TESTS / service).mkdir(parents=True)
    for service in ("iam", "cloudfront"):
        (tmp_path / m.TESTS / service / f"test_{service}.snapshot.json").write_text("{}")

    found, notes = m.snapshots_in(tmp_path, [m.ALL])

    assert [path.parent.name for path in found] == ["cloudfront", "iam"]
    assert notes == []


def test_all_against_a_checkout_without_those_paths_is_a_note(tmp_path):
    found, notes = m.snapshots_in(tmp_path, [m.ALL])

    assert found == []
    assert len(notes) == 1 and "carries no service with a snapshot file" in notes[0]


def test_a_snapshot_below_the_service_directory_is_read_under_that_service(tmp_path):
    # A snapshot sits beside its test, and 137 of the files in the tree this was written against
    # are a directory or two below the service's own door, carrying 1505 recordings between them.
    nested = tmp_path / m.TESTS / "cloudformation" / "engine"
    nested.mkdir(parents=True)
    (nested / "test_conditions.snapshot.json").write_text(json.dumps(
        {"tests/aws/services/cloudformation/engine/test_conditions.py::TestOne::test_plain":
            snapshot_entry({"refused": refusal("ValidationError", "nested", 400)})}),
        encoding="utf-8")
    (nested / "test_conditions.py").write_text(SOURCE, encoding="utf-8")

    found, notes = m.snapshots_in(tmp_path, ["cloudformation"])
    assert [path.name for path in found] == ["test_conditions.snapshot.json"]
    assert notes == []
    assert [path.name for path in m.snapshots_in(tmp_path, [m.ALL])[0]] == [
        "test_conditions.snapshot.json"]

    # And it is labelled with the service rather than with the directory it happens to sit in.
    recorded, notes = m.mine(found, [], False)
    assert [(one.service, one.code) for one in recorded] == [("cloudformation", "ValidationError")]
    assert notes == []


def test_a_checkout_without_the_service_is_a_note(tmp_path):
    found, notes = m.snapshots_in(tmp_path, ["iam"])

    assert found == []
    assert len(notes) == 1 and "is not a directory" in notes[0]


def test_the_tsv_rows_carry_every_field_under_a_header():
    recording = m.Recording("sts", "test_plain", "validated", "10-04-2025, 08:53:12", ".refused",
                            "InvalidParameterValue", "400", "Duplicate tag keys found.")

    rows = m.render([recording], tsv=True).splitlines()

    assert rows[0].startswith("# service\tmarker\trecorded\ttest\tpath\tcode\tstatus\tmessage")
    assert rows[1].split("\t") == ["sts", "validated", "10-04-2025, 08:53:12", "test_plain",
                                   ".refused", "InvalidParameterValue", "400",
                                   "Duplicate tag keys found."]


def test_a_field_that_would_break_the_row_is_escaped():
    # 53 of the messages in the snapshots this was written against carry a newline or a tab.
    message = "two errors detected:" + chr(10) + "first" + chr(9) + "second" + chr(13)
    recording = m.Recording("iam", "test_plain", "validated", "06-03-2025", ".refused",
                            "ValidationError", "400", message)

    rows = m.render([recording], tsv=True).splitlines()

    assert len(rows) == 2, rows
    fields = rows[1].split(chr(9))
    assert len(fields) == 8, fields
    assert fields[7] == ("two errors detected:" + chr(92) + "nfirst" + chr(92) + "tsecond"
                         + chr(92) + "r")


def test_two_classes_sharing_a_test_name_stay_two_tests_in_the_output():
    # 18 of the tests in that tree would otherwise collapse onto another's name, in the lines and
    # in the count, which is the one number a reader takes from the summary.
    snapshot = {
        "tests/aws/services/iam/test_iam.py::TestOne::test_plain": snapshot_entry(
            {"refused": refusal("InvalidInput", "one")}),
        "tests/aws/services/iam/test_iam.py::TestTwo::test_plain": snapshot_entry(
            {"refused": refusal("InvalidInput", "two")}),
    }

    found = m.recordings("iam", snapshot, m.markers(SOURCE), [], True)

    assert [one.test for one in found] == ["test_iam.py::TestOne::test_plain",
                                           "test_iam.py::TestTwo::test_plain"]
    assert m.summary(found).startswith("2 recording(s) over 2 test(s)")


def test_a_parameter_carrying_a_slash_keeps_the_whole_test_name():
    # 33 keys in that tree carry a slash inside the parameter. Cutting the tail at the last slash
    # rather than at the file leaves `name]`, which names nothing.
    key = ("tests/aws/services/stepfunctions/v2/test_sfn_api_activities.py::TestOne"
           "::test_plain[activity/name]")
    snapshot = {key: snapshot_entry({"refused": refusal("ValidationError", "slashed")})}

    found = m.recordings("stepfunctions", snapshot, m.markers(SOURCE), [], False)

    assert [one.test for one in found] == [
        "test_sfn_api_activities.py::TestOne::test_plain[activity/name]"]


def test_the_summary_counts_the_codes_it_found():
    recordings = [
        m.Recording("iam", "a", "validated", "d", ".x", "NoSuchEntity", "404", "gone"),
        m.Recording("iam", "b", "validated", "d", ".x", "NoSuchEntity", "404", "gone"),
        m.Recording("iam", "b", "validated", "d", ".y", None, None, "a text match"),
    ]

    assert m.summary(recordings) == "3 recording(s) over 2 test(s) / codes: NoSuchEntity 2"
