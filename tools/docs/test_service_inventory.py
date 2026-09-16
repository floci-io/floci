"""Tests for service_inventory.

Every test here pins a parser trap that was found by hand-checking the real sources,
and that would otherwise have made this tool produce a *tenth* wrong number in a repo
that already had nine. They are written against synthetic sources so they keep failing
for the right reason when the real files change.

Run with: pytest tools/docs -q  (or: make docs-test)
"""
from __future__ import annotations

from datetime import date

import pytest

import service_inventory as si


# --------------------------------------------------------------------------- #
# Key normalisation
# --------------------------------------------------------------------------- #
def test_key_normalises_across_naming_conventions():
    # The same service, spelled three ways by three sources.
    assert si.key("bcmDataExports") == si.key("bcm-data-exports") == si.key("bcmdataexports")


def test_key_does_not_collide_across_distinct_services():
    assert si.key("rds") != si.key("rds-data")


# --------------------------------------------------------------------------- #
# Descriptor parsing
# --------------------------------------------------------------------------- #
SIMPLE = '''
    descriptor("ssm", "ssm", config.services().ssm().enabled(), true,
            "ssm", storageMode(...), 5000L, null, ServiceProtocol.JSON,
            protocols(ServiceProtocol.JSON),
            Set.of("AmazonSSM."), Set.of("ssm"), Set.of(), Set.of()),
'''


def test_parses_key_config_key_and_status_flag():
    [d] = si.parse_descriptors(SIMPLE)
    assert d["external_key"] == "ssm"
    assert d["config_key"] == "ssm"
    assert d["toggles"] == ["ssm"]
    assert d["in_status"] is True


def test_conjunction_toggle_wrapping_onto_a_continuation_line():
    # rds-data and redshift-data wrap because their enabled expression is a conjunction.
    # A line-oriented parser reads `true` off the *first* line, which for these two is
    # the configKey's line, and gets both the toggles and includeInStatus wrong.
    src = '''
    descriptor("rds-data", "rds-data",
            config.services().rds().enabled() && config.services().rdsData().enabled(), false,
            null, null, 5000L, null, ServiceProtocol.REST_JSON,
            protocols(ServiceProtocol.REST_JSON),
            Set.of(), Set.of("rds-data"), Set.of(), Set.of(RdsDataController.class)),
    '''
    [d] = si.parse_descriptors(src)
    assert d["external_key"] == "rds-data"
    assert d["toggles"] == ["rds", "rdsData"]
    assert d["in_status"] is False


def test_status_flag_is_read_from_the_fourth_argument_not_the_first_true():
    # `includeInStatus=false` on a descriptor whose second line contains other literals.
    src = '''
    descriptor("sts", "iam", config.services().iam().enabled(), false,
            null, null, 5000L, AwsNamespaces.STS, ServiceProtocol.QUERY,
            protocols(ServiceProtocol.QUERY),
            Set.of(), Set.of("sts"), Set.of(), Set.of()),
    '''
    assert si.parse_descriptors(src)[0]["in_status"] is False


def test_nested_commas_do_not_split_an_argument():
    args = si.split_top_level_args('f("a", Set.of("b", "c"), true)', 1)
    assert args == ['"a"', 'Set.of("b", "c")', "true"]


def test_unbalanced_parens_raise_rather_than_returning_a_short_list():
    with pytest.raises(ValueError, match="unbalanced"):
        si.split_top_level_args('f("a", "b"', 1)


def test_non_literal_external_key_raises():
    # check_service_matrix.py catches this case by counting call sites; here it must
    # fail loudly rather than silently dropping a service from every count.
    with pytest.raises(ValueError, match="not a plain string literal"):
        si.parse_descriptors("descriptor(KEY, \"ssm\", true, true, ...)")


# --------------------------------------------------------------------------- #
# Config accessors
# --------------------------------------------------------------------------- #
def test_config_accessors_exclude_docker_network_and_include_non_service_configs():
    # DuckConfig does not end in ServiceConfig, so a `*ServiceConfig` pattern misses it
    # and under-counts by one. dockerNetwork() returns Optional<String> and must not
    # be counted at all.
    src = """
    interface ServicesConfig {
        Optional<String> dockerNetwork();

        SsmServiceConfig ssm();
        SqsServiceConfig sqs();
        DuckConfig duck();
    }
"""
    assert si.parse_config_accessors(src) == ["duck", "sqs", "ssm"]


def test_missing_services_config_interface_raises():
    with pytest.raises(ValueError, match="ServicesConfig not found"):
        si.parse_config_accessors("interface Unrelated { }")


# --------------------------------------------------------------------------- #
# Matrix rows
# --------------------------------------------------------------------------- #
MATRIX = f"""{si.csm.MATRIX_HEADING}

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [DynamoDB](dynamodb.md) | `POST /` | JSON 1.1 | 28 |
| [DynamoDB Streams](dynamodb.md#streams) | `POST /` | JSON 1.1 | 4 |
| [IoT Core](iot.md) | `/things` | REST JSON | 40 |
| [IoT Data](iot.md) | `/topics` | REST JSON | 5 |
| [S3](s3.md) | `/{{bucket}}` | REST XML | 58 |

{si.csm.MATRIX_END_HEADING}
"""


def test_facet_rows_are_detected_by_duplicate_slug_not_by_anchor():
    # IoT Core and IoT Data both link plain `iot.md` with no anchor at all. An
    # anchor-based rule finds only the DynamoDB pair and over-counts pages by one.
    rows = si.parse_matrix_rows(MATRIX)
    assert len(rows) == 5
    assert {r["slug"] for r in rows} == {"dynamodb", "iot", "s3"}
    assert rows[3]["anchor"] is None


def test_rows_outside_the_matrix_headings_are_ignored():
    src = f"| [Elsewhere](elsewhere.md) | x |\n\n{MATRIX}"
    assert all(r["slug"] != "elsewhere" for r in si.parse_matrix_rows(src))


# --------------------------------------------------------------------------- #
# README
# --------------------------------------------------------------------------- #
def test_category_names_do_not_split_inside_parentheses():
    # "IAM Identity Center (SSO Admin, OIDC, Access Portal, SCIM)" is one service name.
    # Splitting on every comma turns it into four, three of which ("OIDC", "SCIM)")
    # are not service names and inflate the count.
    src = (
        "| Category | Services |\n|---|---|\n"
        "| Identity | IAM, IAM Identity Center (SSO Admin, OIDC, Access Portal, SCIM), KMS |\n"
        "\nFor operation-level compatibility, see the Services Overview.\n"
    )
    assert si.parse_readme_category_names(src) == [
        "IAM",
        "IAM Identity Center (SSO Admin, OIDC, Access Portal, SCIM)",
        "KMS",
    ]


def test_detail_rows_take_the_first_cell_only():
    src = (
        "| Service | How it works | Notable features |\n|---|---|---|\n"
        "| SQS | In-process | Standard and FIFO queues, DLQ |\n"
        "</details>\n"
    )
    assert si.parse_readme_detail_rows(src) == ["SQS"]


# --------------------------------------------------------------------------- #
# Reconciliation
# --------------------------------------------------------------------------- #
def _data(**overrides) -> dict:
    base = {
        "descriptors": [
            {"external_key": "iam", "config_key": "iam", "toggles": ["iam"], "in_status": True},
            {"external_key": "sts", "config_key": "iam", "toggles": ["iam"], "in_status": False},
            {"external_key": "s3", "config_key": "s3", "toggles": ["s3"], "in_status": True},
        ],
        "config_accessors": ["iam", "s3", "duck"],
        "yaml_keys": ["iam", "s3", "duck"],
        "yaml_enabled": ["iam", "s3"],
        "packages": ["iam", "s3"],
        "matrix_rows": [
            {"label": "IAM", "slug": "iam", "anchor": None},
            {"label": "AWS Sign-In", "slug": "iam", "anchor": "signin"},
            {"label": "S3", "slug": "s3", "anchor": None},
        ],
        "doc_pages": ["iam", "s3"],
        "readme_categories": ["IAM", "S3"],
        "readme_details": ["S3"],
        "action_tables": ["s3"],
        "aliases": {"sts": "iam"},
        "facet_pages": [],
        "deferred": [],
    }
    base.update(overrides)
    return base


def test_shared_toggle_is_reported_with_every_descriptor_it_gates():
    r = si.reconcile(_data())
    assert r["catalog"]["shared_toggles"] == {"iam": ["iam", "sts"]}
    assert r["counts"]["catalog_toggles"] == 2


def test_facet_descriptor_is_the_non_owner_of_a_shared_page():
    r = si.reconcile(_data())
    assert r["catalog"]["facet_descriptors"] == ["sts"]
    assert r["counts"]["catalog_distinct_products"] == 2


def test_non_aws_entries_are_subtracted_from_the_aws_key_count():
    r = si.reconcile(_data())
    assert r["config"]["non_aws"] == ["duck"]
    assert r["counts"]["yaml_aws_keys"] == 2


def test_config_and_yaml_agree_after_normalisation():
    r = si.reconcile(_data(config_accessors=["rdsData"], yaml_keys=["rds-data"]))
    assert r["config"]["accessor_only"] == []
    assert r["config"]["yaml_only"] == []


def test_unregistered_package_is_reported_when_not_noted():
    r = si.reconcile(_data(packages=["iam", "s3", "newthing"]))
    assert r["packages"]["unregistered"] == ["newthing"]


def test_noted_package_is_not_reported_as_unregistered():
    si.PACKAGE_NOTES["temporary"] = "test fixture"
    try:
        r = si.reconcile(_data(packages=["iam", "s3", "temporary"]))
        assert r["packages"]["unregistered"] == []
        assert "temporary" in r["packages"]["noted"]
    finally:
        del si.PACKAGE_NOTES["temporary"]


def test_facet_row_missing_from_readme_is_separated_from_a_real_gap():
    # "AWS Sign-In" shares iam.md, so its absence from the README is by design.
    # A non-facet row missing from the README is a genuine finding.
    r = si.reconcile(_data(readme_categories=["IAM"]))
    assert r["readme"]["missing_from_categories"] == ["S3"]
    assert r["readme"]["missing_facet_rows"] == ["AWS Sign-In"]


def test_readme_alias_covers_several_matrix_rows_at_once():
    si.README_NAME_ALIASES["Identity"] = ["IAM", "AWS Sign-In"]
    try:
        r = si.reconcile(_data(readme_categories=["Identity", "S3"]))
        assert r["readme"]["unmatched_category_names"] == []
        assert r["readme"]["missing_facet_rows"] == []
    finally:
        del si.README_NAME_ALIASES["Identity"]


def test_readme_name_matching_no_row_is_reported_rather_than_dropped():
    r = si.reconcile(_data(readme_categories=["IAM", "S3", "Imaginary Service"]))
    assert r["readme"]["unmatched_category_names"] == ["Imaginary Service"]


def test_deferral_within_the_warning_window_is_flagged():
    deferred = [{"key": "x", "reason": "r", "by": "2026-09-15"}]
    r = si.reconcile(_data(deferred=deferred), today=date(2026, 9, 14))
    assert r["docs"]["deferred_expiring"] == deferred


def test_deferral_beyond_the_warning_window_is_not_flagged():
    deferred = [{"key": "x", "reason": "r", "by": "2027-01-01"}]
    r = si.reconcile(_data(deferred=deferred), today=date(2026, 9, 14))
    assert r["docs"]["deferred_expiring"] == []


# --------------------------------------------------------------------------- #
# End to end, against the real repo
# --------------------------------------------------------------------------- #
def test_real_sources_parse_and_render():
    result = si.reconcile(si.collect(si._repo_root()))
    counts = result["counts"]
    # Not pinned to exact values: they move every time a service lands. What must hold
    # is that every source parsed to something plausible and the two gated equalities
    # this tool reports as green really are green.
    assert all(v > 50 for v in counts.values())
    assert counts["matrix_pages"] == counts["doc_pages"]
    assert counts["config_accessors"] == counts["yaml_keys"]
    assert result["docs"]["pages_without_row"] == []
    assert result["packages"]["unregistered"] == []
    assert "THE ANSWER" in si.render(result)


def test_report_never_suggests_publishing_a_count():
    # The hard rule the tool exists to protect, asserted rather than trusted to prose.
    text = si.render(si.reconcile(si.collect(si._repo_root())))
    assert "Never write any of them into README.md or docs/" in text
