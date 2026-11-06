"""Tests for the service-linked role table generator.

The traps here are all ones the real data walked into, so each has a case: an underscore
that is part of a role name rather than a suffix separator, a policy that matches
suffixed roles with a trailing separator, and a wildcard ARN that names a role without
saying which principal mints it.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

import service_linked_roles as slr


def write(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / name
    path.write_text(text)
    return path


def policy_documents(tmp_path: Path, *arns: str) -> Path:
    statements = ",".join(f'{{"Resource":"{arn}"}}' for arn in arns)
    return write(tmp_path, "documents.json", f'{{"P":[{statements}]}}')


def snapshot(tmp_path: Path, entries: dict[str, dict]) -> Path:
    return write(tmp_path, "snap.json", json.dumps(entries))


def lifecycle(service: str, role: str) -> tuple[str, dict]:
    key = f"tests/aws/services/iam/test_iam.py::T::test_service_role_lifecycle[{service}]"
    return key, {"recorded-content": {"describe-response": {"Role": {"RoleName": role}}}}


def with_suffix(service: str, role: str) -> tuple[str, dict]:
    key = (f"tests/aws/services/iam/test_iam.py::T::"
           f"test_service_role_lifecycle_custom_suffix[{service}]")
    return key, {"recorded-content": {"describe-response": {"Role": {"RoleName": role}}}}


def suffix_refused(service: str) -> tuple[str, dict]:
    key = (f"tests/aws/services/iam/test_iam.py::T::"
           f"test_service_role_lifecycle_custom_suffix_not_allowed[{service}]")
    return key, {"recorded-content": {"custom-suffix-not-allowed": {"Error": {"Code": "InvalidInput"}}}}


class TestPolicyDocumentExtraction:
    def test_an_underscore_inside_a_role_name_survives(self, tmp_path):
        path = policy_documents(tmp_path, "arn:aws:iam::*:role/aws-service-role/"
                                "cassandra.application-autoscaling.amazonaws.com/"
                                "AWSServiceRoleForApplicationAutoScaling_CassandraTable")
        names = slr.names_from_policy_documents(path)
        assert names["cassandra.application-autoscaling.amazonaws.com"] == (
            "AWSServiceRoleForApplicationAutoScaling_CassandraTable")

    def test_a_trailing_separator_from_a_wildcard_policy_is_dropped(self, tmp_path):
        path = policy_documents(tmp_path, "arn:aws:iam::*:role/aws-service-role/"
                                "connect.amazonaws.com/AWSServiceRoleForAmazonConnect_")
        assert slr.names_from_policy_documents(path) == {
            "connect.amazonaws.com": "AWSServiceRoleForAmazonConnect"}

    def test_a_wildcard_arn_with_no_principal_path_is_ignored(self, tmp_path):
        """This one matters: guessing from it once gave GuardDuty another role's name."""
        path = policy_documents(
            tmp_path, "arn:aws:iam::*:role/*AWSServiceRoleForAmazonGuardDutyMalwareProtection")
        assert slr.names_from_policy_documents(path) == {}


class TestSnapshotExtraction:
    def test_a_name_and_a_refusal_come_out_of_one_pass(self, tmp_path):
        path = snapshot(tmp_path, dict([lifecycle("ecs.amazonaws.com", "AWSServiceRoleForECS"),
                                        suffix_refused("ecs.amazonaws.com")]))
        assert slr.rows_from_snapshot(path) == {
            "ecs.amazonaws.com": ("AWSServiceRoleForECS", slr.SUFFIX_REFUSED)}

    def test_the_suffix_placeholder_is_stripped_from_a_suffixed_create(self, tmp_path):
        path = snapshot(tmp_path, dict([
            with_suffix("autoscaling.amazonaws.com", "AWSServiceRoleForAutoScaling_<suffix>")]))
        assert slr.rows_from_snapshot(path) == {
            "autoscaling.amazonaws.com": ("AWSServiceRoleForAutoScaling", slr.SUFFIX_ALLOWED)}

    def test_a_service_with_no_recording_is_unknown_rather_than_permitted_or_refused(self, tmp_path):
        path = snapshot(tmp_path, dict([lifecycle("acm.amazonaws.com",
                                                  "AWSServiceRoleForCertificateManager")]))
        assert slr.rows_from_snapshot(path)["acm.amazonaws.com"][1] == slr.SUFFIX_UNKNOWN


class TestBuild:
    def test_a_recording_outranks_a_policy_document(self, tmp_path):
        documents = policy_documents(tmp_path, "arn:aws:iam::*:role/aws-service-role/"
                                     "x.amazonaws.com/AWSServiceRoleForFromDocuments")
        snap = snapshot(tmp_path, dict([lifecycle("x.amazonaws.com",
                                                  "AWSServiceRoleForFromRecording")]))
        rows, problems, _notes = slr.build(documents, snap, documented={}, conflicts={
            "x.amazonaws.com": ("AWSServiceRoleForFromDocuments",
                                "AWSServiceRoleForFromRecording")})
        assert problems == []
        assert [(r.role, r.source) for r in rows] == [("AWSServiceRoleForFromRecording", "recording")]

    def test_an_unrecorded_disagreement_is_a_problem_rather_than_a_silent_preference(self, tmp_path):
        documents = policy_documents(tmp_path, "arn:aws:iam::*:role/aws-service-role/"
                                     "y.amazonaws.com/AWSServiceRoleForOne")
        snap = snapshot(tmp_path, dict([lifecycle("y.amazonaws.com", "AWSServiceRoleForTwo")]))
        rows, problems, _notes = slr.build(documents, snap, conflicts={}, documented={})
        assert len(problems) == 1
        assert "the sources disagree" in problems[0]

    def test_a_stale_conflict_entry_is_reported(self, tmp_path):
        _, problems, _notes = slr.build(policy_documents(tmp_path), None, documented={},
                                conflicts={"gone.amazonaws.com": ("a", "b")})
        assert any("is in no source" in problem for problem in problems)


class TestDerivedName:
    @pytest.mark.parametrize("principal,expected", [
        ("batch.amazonaws.com", "AWSServiceRoleForBatch"),
        ("access-analyzer.amazonaws.com", "AWSServiceRoleForAccessAnalyzer"),
        ("ops.apigateway.amazonaws.com", "AWSServiceRoleForOpsApigateway"),
    ])
    def test_the_fallback_matches_what_the_service_derives(self, principal, expected):
        assert slr.derived_name(principal) == expected


def table(tmp_path: Path, *rows: str, recordings: str = "none") -> Path:
    header = ("# generated, do not hand-edit\n"
              f"# recordings: {recordings} (fixture)\n")
    return write(tmp_path, "table.tsv", header + "".join(row + "\n" for row in rows))


ROW = "{}\t{}\t{}\t{}".format


class TestCheck:
    """The CI gate. These were probed by hand first; a gate nothing tests is not a gate."""

    def clean(self, tmp_path: Path) -> tuple[Path, Path]:
        documents = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/aaa.amazonaws.com/AWSServiceRoleForAaa",
            "arn:aws:iam::*:role/aws-service-role/bbb.amazonaws.com/AWSServiceRoleForBbb")
        return table(
            tmp_path,
            ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "unknown", "policy-documents"),
            ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"),
        ), documents

    def test_a_table_matching_the_vendored_data_has_no_problems(self, tmp_path):
        tsv, documents = self.clean(tmp_path)
        assert slr.check(tsv, documents, documented={}) == []

    def test_a_hand_edited_name_on_a_policy_documents_row_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForEdited", "unknown", "policy-documents"),
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"))
        problems = slr.check(tsv, documents, documented={})
        assert any("the vendored policy documents now say" in problem for problem in problems)

    def test_a_vendored_service_missing_from_the_table_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "unknown", "policy-documents"))
        problems = slr.check(tsv, documents, documented={})
        assert any("but not in the table" in problem for problem in problems)

    def test_suffix_support_claimed_without_a_recording_is_caught(self, tmp_path):
        """Only a recorded create can establish it, so any other source must say unknown."""
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "refused", "policy-documents"),
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"))
        problems = slr.check(tsv, documents, documented={})
        assert any("only" in problem and "recording" in problem for problem in problems)

    def test_an_unsorted_table_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"),
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "unknown", "policy-documents"))
        assert any("not sorted" in problem for problem in slr.check(tsv, documents, documented={}))

    def test_a_duplicated_principal_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "unknown", "policy-documents"),
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "unknown", "policy-documents"),
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"))
        assert any("appears twice" in problem for problem in slr.check(tsv, documents, documented={}))

    def test_a_name_without_the_aws_prefix_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "NotAServiceLinkedRole", "unknown", "policy-documents"),
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "policy-documents"))
        assert any("does not start with" in problem for problem in slr.check(tsv, documents, documented={}))

    def test_an_unknown_source_or_suffix_state_is_caught(self, tmp_path):
        _, documents = self.clean(tmp_path)
        tsv = table(tmp_path,
                    ROW("aaa.amazonaws.com", "AWSServiceRoleForAaa", "maybe", "policy-documents"),
                    ROW("bbb.amazonaws.com", "AWSServiceRoleForBbb", "unknown", "hearsay"))
        problems = slr.check(tsv, documents, documented={})
        assert any("is not a suffix state" in problem for problem in problems)
        assert any("is not a source" in problem for problem in problems)

    def test_a_missing_table_says_how_to_make_one(self, tmp_path):
        _, documents = self.clean(tmp_path)
        problems = slr.check(tmp_path / "absent.tsv", documents, documented={})
        assert len(problems) == 1
        assert "slr-table" in problems[0]


class TestRoundTrip:
    def test_what_render_writes_parse_table_reads_back(self, tmp_path):
        documents = policy_documents(
            tmp_path, "arn:aws:iam::*:role/aws-service-role/aaa.amazonaws.com/AWSServiceRoleForAaa")
        snap = snapshot(tmp_path, dict([lifecycle("zzz.amazonaws.com", "AWSServiceRoleForZzz"),
                                        suffix_refused("zzz.amazonaws.com")]))
        rows, problems, _notes = slr.build(documents, snap, conflicts={}, documented={})
        assert problems == []

        rendered = write(tmp_path, "rendered.tsv", slr.render(rows))
        read_back = slr.parse_table(rendered)
        assert [(r.principal, r.role, r.suffix, r.source) for r in read_back] == [
            (r.principal, r.role, r.suffix, r.source) for r in rows]

    def test_the_header_counts_what_it_wrote(self, tmp_path):
        """The header states totals, and a header that counts wrong is how a table starts lying."""
        documents = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/aaa.amazonaws.com/AWSServiceRoleForAaa",
            "arn:aws:iam::*:role/aws-service-role/bbb.amazonaws.com/AWSServiceRoleForBbb")
        rows, _p, _n = slr.build(documents, None, conflicts={}, documented={})
        rendered = slr.render(rows)
        assert f"{len(rows)} services:" in rendered
        assert f"{len(rows)} policy-documents" in rendered


class TestWildcardSuffix:
    """
    A name followed by `*` is a prefix pattern, not a name. email.cognito-idp is the proof: the
    policy writes AWSServiceRoleForAmazonCognitoIdpEmail* where the recorded create returns
    AWSServiceRoleForAmazonCognitoIdpEmailService, so reading the prefix invents a role.
    """

    def test_a_wildcard_suffixed_arn_is_not_read_as_a_name(self, tmp_path):
        path = policy_documents(
            tmp_path, "arn:aws:iam::*:role/aws-service-role/email.cognito-idp.amazonaws.com/"
                      "AWSServiceRoleForAmazonCognitoIdpEmail*")
        assert slr.names_from_policy_documents(path) == {}

    def test_an_exact_arn_beside_a_wildcard_one_still_counts(self, tmp_path):
        path = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/aaa.amazonaws.com/AWSServiceRoleForAaa*",
            "arn:aws:iam::*:role/aws-service-role/aaa.amazonaws.com/AWSServiceRoleForAaaExact")
        assert slr.names_from_policy_documents(path) == {
            "aaa.amazonaws.com": "AWSServiceRoleForAaaExact"}


class TestNameCollisions:
    """
    A role name is unique per account and createServiceLinkedRole refuses one already taken, so
    two principals sharing a name makes the second principal's create fail as a duplicate. The
    policy documents produce exactly that, pointing maintenance.elasticbeanstalk at the parent's
    AWSServiceRoleForElasticBeanstalk.
    """

    def test_the_weaker_row_is_dropped_rather_than_asserting_a_colliding_name(self, tmp_path):
        documents = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/parent.amazonaws.com/AWSServiceRoleForShared",
            "arn:aws:iam::*:role/aws-service-role/child.parent.amazonaws.com/AWSServiceRoleForShared")
        snap = snapshot(tmp_path, dict([lifecycle("parent.amazonaws.com", "AWSServiceRoleForShared")]))
        rows, problems, notes = slr.build(documents, snap, conflicts={}, documented={})

        assert problems == []
        assert [r.principal for r in rows] == ["parent.amazonaws.com"]
        assert any("child.parent.amazonaws.com dropped" in note for note in notes)

    def test_a_collision_between_equal_sources_is_a_problem_for_a_human(self, tmp_path):
        documents = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/one.amazonaws.com/AWSServiceRoleForShared",
            "arn:aws:iam::*:role/aws-service-role/two.amazonaws.com/AWSServiceRoleForShared")
        _, problems, _ = slr.build(documents, None, conflicts={}, documented={})
        assert any("cannot be shared" in problem for problem in problems)

    def test_check_does_not_demand_a_principal_the_collision_guard_dropped(self, tmp_path):
        """Otherwise the gate asks for a row build() deliberately refuses to emit, forever."""
        documents = policy_documents(
            tmp_path,
            "arn:aws:iam::*:role/aws-service-role/parent.amazonaws.com/AWSServiceRoleForShared",
            "arn:aws:iam::*:role/aws-service-role/child.parent.amazonaws.com/AWSServiceRoleForShared")
        tsv = table(tmp_path,
                    ROW("parent.amazonaws.com", "AWSServiceRoleForShared", "unknown", "policy-documents"))
        assert slr.check(tsv, documents, documented={}) == []


class TestDocumentedRows:
    """docs rows cite a page nothing here can fetch, but the generator holds the expected value."""

    def test_a_docs_row_disagreeing_with_the_generator_is_caught(self, tmp_path):
        documents = policy_documents(tmp_path)
        tsv = table(tmp_path, ROW("x.amazonaws.com", "AWSServiceRoleForEdited", "unknown", "docs"))
        problems = slr.check(tsv, documents,
                             documented={"x.amazonaws.com": ("AWSServiceRoleForReal", "a url")})
        assert any("DOCUMENTED says AWSServiceRoleForReal" in problem for problem in problems)

    def test_a_docs_row_with_no_generator_entry_is_caught(self, tmp_path):
        documents = policy_documents(tmp_path)
        tsv = table(tmp_path, ROW("x.amazonaws.com", "AWSServiceRoleForX", "unknown", "docs"))
        problems = slr.check(tsv, documents, documented={})
        assert any("no DOCUMENTED entry" in problem for problem in problems)

    def test_a_documented_service_missing_from_the_table_is_caught(self, tmp_path):
        documents = policy_documents(tmp_path)
        tsv = table(tmp_path)
        problems = slr.check(tsv, documents,
                             documented={"x.amazonaws.com": ("AWSServiceRoleForX", "a url")})
        assert any("but not in the table" in problem for problem in problems)


class TestSnapshotFingerprint:
    """
    The recording half of the table cannot be rechecked offline. A digest in the header turns the
    next refresh into a diff against a known source rather than an act of faith.
    """

    def test_the_header_records_the_digest_of_the_snapshot_it_was_built_from(self, tmp_path):
        snap = snapshot(tmp_path, dict([lifecycle("x.amazonaws.com", "AWSServiceRoleForX")]))
        rows, _, _ = slr.build(policy_documents(tmp_path), snap, conflicts={}, documented={})
        digest = slr.snapshot_fingerprint(snap)
        assert len(digest) == 64
        assert f"recordings: {digest} " in slr.render(rows, digest)

    def test_a_different_snapshot_gives_a_different_digest(self, tmp_path):
        one = snapshot(tmp_path, dict([lifecycle("x.amazonaws.com", "AWSServiceRoleForX")]))
        two = write(tmp_path, "other.json", one.read_text() + " ")
        assert slr.snapshot_fingerprint(one) != slr.snapshot_fingerprint(two)

    def test_no_snapshot_is_recorded_as_none_rather_than_a_made_up_digest(self):
        assert slr.snapshot_fingerprint(None) == "none"

    def test_the_digest_is_read_back_out_of_a_table(self, tmp_path):
        digest = "a" * 64
        tsv = table(tmp_path, ROW("a.amazonaws.com", "AWSServiceRoleForA", "unknown", "docs"),
                    recordings=digest)
        assert slr.fingerprint_in(tsv) == digest

    def test_a_table_that_lost_its_recordings_line_is_caught(self, tmp_path):
        tsv = write(tmp_path, "nodigest.tsv",
                    "# generated\n"
                    + ROW("a.amazonaws.com", "AWSServiceRoleForA", "unknown", "policy-documents")
                    + "\n")
        documents = policy_documents(
            tmp_path, "arn:aws:iam::*:role/aws-service-role/a.amazonaws.com/AWSServiceRoleForA")
        problems = slr.check(tsv, documents, documented={})
        assert any("no `recordings:` line" in problem for problem in problems)

    def test_recording_rows_with_no_snapshot_recorded_is_caught(self, tmp_path):
        """Otherwise the suffix column rests on a source the table does not name."""
        tsv = table(tmp_path,
                    ROW("a.amazonaws.com", "AWSServiceRoleForA", "refused", "recording"),
                    recordings="none")
        problems = slr.check(tsv, policy_documents(tmp_path), documented={})
        assert any("built from no snapshot" in problem for problem in problems)
