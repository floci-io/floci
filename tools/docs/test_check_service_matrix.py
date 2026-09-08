"""Tests for check_service_matrix.

Run with: pytest tools/docs -q  (or: make docs-test)
"""
from __future__ import annotations

from datetime import date

import pytest

import check_service_matrix as c


# --------------------------------------------------------------------------- #
# Extraction
# --------------------------------------------------------------------------- #
def test_extract_descriptor_keys_basic():
    src = '''
        descriptor("ssm", "ssm", config.services().ssm().enabled(), true,
                "ssm", storageMode(...), 5000L, null, ServiceProtocol.JSON,
                protocols(ServiceProtocol.JSON),
                Set.of("AmazonSSM."), Set.of("ssm"), Set.of(), Set.of()),
        descriptor("sqs", "sqs", config.services().sqs().enabled(), true, ...),
    '''
    assert c.extract_descriptor_keys(src) == ["ssm", "sqs"]


def test_extract_descriptor_keys_ignores_other_string_literals():
    # Only the first argument of descriptor(...) counts; targetPrefixes, credential
    # scopes and the like must not be picked up even though they're also quoted.
    src = 'descriptor("kafka", "msk", true, true, "msk", null, 5000L, null, X, Y, Set.of("kafka-signing-prefix."), Set.of("kafka"), Set.of(), Set.of())'
    assert c.extract_descriptor_keys(src) == ["kafka"]


def test_count_descriptor_calls_matches_literal_keys_when_all_literal():
    src = '''
        descriptor("ssm", "ssm", true, true, ...),
        descriptor("sqs", "sqs", true, true, ...),
    '''
    assert c.count_descriptor_calls(src) == len(c.extract_descriptor_keys(src)) == 2


def test_count_descriptor_calls_excludes_the_definition():
    src = '''
        private static ServiceDescriptor descriptor(
                String externalKey,
                String configKey) {
            return new ServiceDescriptor(externalKey, configKey);
        }
    '''
    assert c.count_descriptor_calls(src) == 0
    assert c.extract_descriptor_keys(src) == []


def test_count_descriptor_calls_catches_a_non_literal_key():
    # A future externalKey built from a variable/constant instead of a plain string
    # literal must not be silently invisible: the call-site count and the literal-key
    # count have to disagree so main() can fail loudly instead of under-counting.
    src = '''
        descriptor("ssm", "ssm", true, true, ...),
        descriptor(SOME_CONSTANT, "other", true, true, ...),
    '''
    assert c.count_descriptor_calls(src) == 2
    assert len(c.extract_descriptor_keys(src)) == 1


def test_extract_matrix_slugs_scoped_to_table():
    md = """
# Services Overview

Some prose with an unrelated [link](not-a-service.md) that must not be picked up.

## Service Matrix

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | ... | JSON 1.1 | 22 |
| [CloudWatch Metrics](cloudwatch.md#metrics) | ... | Query | 11 |

## Common Setup

Another [link](also-not-a-service.md) below the table.
"""
    assert c.extract_matrix_slugs(md) == {"ssm", "cloudwatch"}


def test_extract_matrix_slugs_strips_anchor():
    md = "## Service Matrix\n\n| [API Gateway v2](api-gateway.md#v2) | | | 48 |\n\n## Common Setup\n"
    assert c.extract_matrix_slugs(md) == {"api-gateway"}


def test_extract_matrix_slugs_raises_on_missing_heading():
    # A renamed/reordered heading must fail loudly rather than silently returning an
    # empty slug set, which would make every registered service look undocumented.
    md = "# Services Overview\n\nNo matrix heading here.\n"
    with pytest.raises(ValueError, match="Service Matrix"):
        c.extract_matrix_slugs(md)


# --------------------------------------------------------------------------- #
# Classification
# --------------------------------------------------------------------------- #
def test_find_undocumented_exact_match_needs_no_alias():
    undocumented, expired = c.find_undocumented(
        keys=["ssm", "sqs"],
        slugs={"ssm", "sqs"},
        aliases={},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == []
    assert expired == []


def test_find_undocumented_alias_resolves():
    undocumented, expired = c.find_undocumented(
        keys=["kafka"],
        slugs={"msk"},
        aliases={"kafka": "msk"},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == []


def test_find_undocumented_flags_genuine_gap():
    undocumented, expired = c.find_undocumented(
        keys=["swf"],
        slugs={"ssm"},
        aliases={},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == ["swf"]
    assert expired == []


def test_find_undocumented_unexpired_deferred_is_silenced():
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2026, 9, 15))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs=set(),
        aliases={},
        deferred=[d],
        today=date(2026, 8, 22),
    )
    assert undocumented == []
    assert expired == []


def test_find_undocumented_expired_deferred_reports_and_still_flags():
    # This is the case the mandatory `by:` date exists to catch: a deferred entry that
    # has run past its expiry is reported distinctly (so the message names the date
    # and reason) AND still counts as undocumented - it cannot silently keep passing.
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2026, 9, 15))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs=set(),
        aliases={},
        deferred=[d],
        today=date(2026, 10, 1),
    )
    assert undocumented == ["bedrock-agentcore"]
    assert expired == [d]


def test_find_undocumented_deferred_entry_already_resolved_is_not_flagged():
    # If the row was added but the registry entry wasn't cleaned up, the key resolves
    # via the matrix itself regardless of expiry - no warning either way.
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2020, 1, 1))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs={"bedrock-agentcore"},
        aliases={},
        deferred=[d],
        today=date(2026, 8, 22),
    )
    assert undocumented == []
    assert expired == []


# --------------------------------------------------------------------------- #
# Unlinked pages
# --------------------------------------------------------------------------- #
def test_find_unlinked_pages_flags_a_page_with_no_row(tmp_path):
    for name in ("index.md", "ssm.md", "elb.md", "elb-classic.md"):
        (tmp_path / name).write_text("# page\n")
    # index.md is the matrix page itself; elb.md is linked; elb-classic.md is not.
    assert c.find_unlinked_pages(tmp_path, slugs={"ssm", "elb"}, facet_pages=set()) == ["elb-classic"]


def test_find_unlinked_pages_honours_facet_exemption(tmp_path):
    for name in ("index.md", "lambda.md", "lambda-microvms.md"):
        (tmp_path / name).write_text("# page\n")
    assert c.find_unlinked_pages(tmp_path, slugs={"lambda"}, facet_pages={"lambda-microvms"}) == []


def test_find_unlinked_pages_ignores_non_markdown(tmp_path):
    (tmp_path / "ssm.md").write_text("# page\n")
    (tmp_path / "diagram.svg").write_text("<svg/>")
    assert c.find_unlinked_pages(tmp_path, slugs={"ssm"}, facet_pages=set()) == []
