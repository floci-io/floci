"""Tests for partition_literals.

Run with: pytest tools/partition -q  (or: make partition-test)
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path

import pytest

import partition_literals as p


# --------------------------------------------------------------------------- #
# Lexer
# --------------------------------------------------------------------------- #
def literal_texts(src: str) -> list[str]:
    return [literal.text for literal in p.scan_java(src)[0]]


def test_scan_java_extracts_plain_string_literals_with_line_numbers():
    src = 'String a = "arn:aws:s3:::b";\nString b = "x";\n'
    literals, escapes = p.scan_java(src)
    assert [(l.line, l.text) for l in literals] == [(1, "arn:aws:s3:::b"), (2, "x")]
    assert escapes == {}


def test_scan_java_ignores_line_and_block_comments():
    src = (
        '// "arn:aws:iam::123:root" in a line comment\n'
        '/* "arn:aws:iam::123:root"\n   spanning lines */\n'
        '/** {@code arn:aws-us-gov:iam::...} */\n'
        'String a = "kept";\n'
    )
    literals, _ = p.scan_java(src)
    assert [(l.line, l.text) for l in literals] == [(5, "kept")]


def test_scan_java_keeps_double_slash_inside_a_string():
    # A URL is not a comment start, so the host after it must still be seen.
    assert literal_texts('String u = "https://sts.amazonaws.com/x";') == ["https://sts.amazonaws.com/x"]


def test_scan_java_handles_escaped_quotes_and_char_literals():
    src = "char q = '\"'; String s = \"say \\\"hi\\\" arn:aws:sqs\"; char b = '\\\\';"
    assert literal_texts(src) == ['say \\"hi\\" arn:aws:sqs']


def test_scan_java_splits_text_blocks_per_line():
    src = (
        'String policy = """\n'
        '    {"Principal": {"Service": "ec2.amazonaws.com"},\n'
        '     "Resource": "arn:aws:s3:::b/*"}\n'
        '    """;\n'
        'String after = "tail";\n'
    )
    literals, _ = p.scan_java(src)
    assert [l.line for l in literals] == [2, 3, 5]
    assert "ec2.amazonaws.com" in literals[0].text
    assert "arn:aws:s3:::b/*" in literals[1].text
    assert literals[2].text == "tail"


def test_scan_java_records_the_escape_comment_by_line():
    src = (
        'String a = "arn:aws:iam::aws:policy/X"; // partition-literal: managed-policy owner is always aws\n'
        'String b = "arn:aws:iam::aws:policy/Y"; // an ordinary comment\n'
    )
    _, escapes = p.scan_java(src)
    assert escapes == {1: "managed-policy owner is always aws"}


def test_scan_java_escape_needs_a_reason():
    _, escapes = p.scan_java('String a = "arn:aws:x"; // partition-literal:   \n')
    assert escapes == {}


# --------------------------------------------------------------------------- #
# Categories
# --------------------------------------------------------------------------- #
def categories_of(text: str) -> list[str]:
    return [c.name for c in p.CATEGORIES if c.regex.search(text)]


@pytest.mark.parametrize("text", [
    "arn:aws:iam::123456789012:role/x",
    "arn:aws:states:::lambda:invoke",
    "arn:aws-cn:greengrass:::runtime/function/executable",
    "arn:aws-us-gov:iam::aws:policy/AdministratorAccess",
    "^arn:aws:iam::(\\d{12}):role/.+$",
])
def test_arn_literal_matches_any_single_partition_prefix(text):
    assert "arn-literal" in categories_of(text)


@pytest.mark.parametrize("text", [
    "^arn:aws[a-zA-Z-]*:sns:[a-z0-9-]+:\\d{12}:.+$",
    "^arn:aws(?:-eusc|-cn|-us-gov|-iso|-iso-[a-z])?:iam::",
    "arn:aws(-[^:]+)?:iam::([0-9]{12})?:role/.+",
    "^arn:aws[^:]*:elasticloadbalancing:",
    "arn:aws(?:-[a-z]{1,5}){0,3}:sso:::instance/",
])
def test_arn_regex_dialect_matches_hand_rolled_partition_alternations(text):
    assert categories_of(text) == ["arn-regex-dialect"]


def test_arn_regex_dialect_does_not_match_a_plain_literal():
    assert "arn-regex-dialect" not in categories_of("arn:aws:iam::123456789012:role/x")


@pytest.mark.parametrize("text,expected", [
    ("ec2.us-east-1.amazonaws.com", True),
    ("https://signin.aws.amazon.com/saml", True),
    ("d111111abcdef8.cloudfront.net", True),
    ("public.ecr.aws/lambda/java", True),
    ("ec2.cn-north-1.amazonaws.com.cn", False),
    ("c2s.ic.gov", False),
])
def test_dns_suffix_matches_commercial_hosts_only(text, expected):
    assert ("dns-suffix" in categories_of(text)) is expected


@pytest.mark.parametrize("text,expected", [
    ("Z2FDTNDATAQYW2", True),
    ("Z35SXDOTRQ7X7K", True),
    ("Z2FDTNDATAQYW2/extra", False),
    ("ZONE", False),
])
def test_hosted_zone_id_matches_whole_literals_only(text, expected):
    assert ("hosted-zone-id" in categories_of(text)) is expected


@pytest.mark.parametrize("text,expected", [
    ("us-east-1", True),
    ("us-gov-west-1", True),
    ("eusc-de-east-1", True),
    ("us-isob-east-1", True),
    ("ec2.eu-central-1.amazonaws.com", True),
    ("us-east-1a", True),
    ("i-us-east-1", False),
    ("us-east-10x", False),
    ("aws-global", False),
])
def test_region_literal_matches_region_and_zone_ids(text, expected):
    assert ("region-literal" in categories_of(text)) is expected


def test_region_literal_is_report_only_and_the_rest_are_gated():
    gated = {c.name for c in p.CATEGORIES if c.gated}
    assert gated == {"arn-literal", "arn-regex-dialect", "dns-suffix", "hosted-zone-id"}
    assert not p.CATEGORY_BY_NAME["region-literal"].gated


# --------------------------------------------------------------------------- #
# Findings, allow-list, escapes
# --------------------------------------------------------------------------- #
def write_java(root: Path, rel: str, body: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


def test_collect_findings_counts_each_occurrence_and_skips_comments(tmp_path):
    write_java(tmp_path, "a/A.java", (
        'class A {\n'
        '    // "arn:aws:iam::1:root"\n'
        '    String two = "arn:aws:s3:::x arn:aws:s3:::y";\n'
        '    String host = "sqs.us-east-1.amazonaws.com";\n'
        '}\n'
    ))
    findings = p.collect_findings(tmp_path, [])
    counted = p.count(findings)
    assert counted == Counter({
        ("arn-literal", "a/A.java"): 2,
        ("dns-suffix", "a/A.java"): 1,
        ("region-literal", "a/A.java"): 1,
    })
    assert {f.line for f in findings} == {3, 4}


def test_allowlist_file_and_pattern_excuse_but_still_report(tmp_path):
    write_java(tmp_path, "ns/AwsNamespaces.java", 'class N { String s3 = "http://s3.amazonaws.com/doc/2006-03-01/"; }\n')
    # A pattern excuses the whole source line, so the counted host sits on its own line.
    write_java(tmp_path, "s3/Acl.java", (
        'class A {\n'
        '    String g = "http://acs.amazonaws.com/groups/global/AllUsers";\n'
        '    String h = "s3.amazonaws.com";\n'
        '}\n'
    ))
    rules = [
        p.AllowRule("file", "ns/AwsNamespaces.java", "namespaces are invariant"),
        p.AllowRule("pattern", r"acs\.amazonaws\.com/groups/", "canned ACL URIs"),
    ]
    findings = p.collect_findings(tmp_path, rules)
    assert p.count(findings) == Counter({("dns-suffix", "s3/Acl.java"): 1})
    excuses = sorted(f.excuse for f in findings if f.excuse)
    assert excuses == [
        "allowlist file: namespaces are invariant",
        "allowlist pattern 'acs\\\\.amazonaws\\\\.com/groups/': canned ACL URIs",
    ]


def test_escape_comment_excuses_only_its_own_line(tmp_path):
    write_java(tmp_path, "x/X.java", (
        'class X {\n'
        '    String a = "arn:aws:iam::aws:policy/A"; // partition-literal: owner slot is literal aws\n'
        '    String b = "arn:aws:iam::aws:policy/B";\n'
        '}\n'
    ))
    findings = p.collect_findings(tmp_path, [])
    assert p.count(findings) == Counter({("arn-literal", "x/X.java"): 1})
    escaped = [f for f in findings if f.excuse]
    assert [(f.line, f.excuse) for f in escaped] == [(2, "escape: owner slot is literal aws")]


def test_load_allowlist_rejects_an_entry_without_a_reason(tmp_path):
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text("patterns:\n  - regex: foo\n    reason: '  '\n")
    with pytest.raises(ValueError, match="no reason"):
        p.load_allowlist(allowlist)


def test_load_allowlist_reads_files_and_patterns(tmp_path):
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text(
        "files:\n  - path: a/B.java\n    reason: why\npatterns:\n  - regex: x\\.y\n    reason: because\n"
    )
    assert p.load_allowlist(allowlist) == [
        p.AllowRule("file", "a/B.java", "why"),
        p.AllowRule("pattern", "x\\.y", "because"),
    ]


def test_repo_allowlist_loads_and_every_pattern_compiles():
    import re
    for rule in p.load_allowlist(p.ALLOWLIST):
        if rule.kind == "pattern":
            re.compile(rule.value)


# --------------------------------------------------------------------------- #
# Baseline and the gate
# --------------------------------------------------------------------------- #
def findings_for(tmp_path: Path, files: dict[str, str]) -> list[p.Finding]:
    for rel, body in files.items():
        write_java(tmp_path, rel, body)
    return p.collect_findings(tmp_path, [])


def test_baseline_round_trips_only_gated_categories(tmp_path):
    findings = findings_for(tmp_path, {
        "a/A.java": 'class A { String r = "us-east-1"; String a = "arn:aws:s3:::b"; }\n',
    })
    baseline = tmp_path / "baseline.tsv"
    written = p.write_baseline(baseline, findings)
    assert written == Counter({("arn-literal", "a/A.java"): 1})
    assert p.read_baseline(baseline) == written
    text = baseline.read_text()
    assert text.startswith("# category\tpath\tcount\n")
    assert "region-literal" not in text


def test_read_baseline_rejects_malformed_rows(tmp_path):
    baseline = tmp_path / "baseline.tsv"
    baseline.write_text("arn-literal\ta/A.java\n")
    with pytest.raises(ValueError, match="malformed"):
        p.read_baseline(baseline)
    baseline.write_text("nope\ta/A.java\t1\n")
    with pytest.raises(ValueError, match="unknown category"):
        p.read_baseline(baseline)


def test_check_is_green_when_counts_match(tmp_path):
    findings = findings_for(tmp_path, {"a/A.java": 'class A { String a = "arn:aws:s3:::b"; }\n'})
    baseline = Counter({("arn-literal", "a/A.java"): 1})
    assert p.check(findings, baseline) == []


def test_check_fails_a_literal_in_a_file_the_baseline_does_not_know(tmp_path):
    findings = findings_for(tmp_path, {"n/New.java": 'class N { String a = "arn:aws:s3:::b"; }\n'})
    problems = p.check(findings, Counter())
    assert len(problems) == 1
    assert "1 arn-literal literal(s) with no baseline entry for this file" in problems[0]
    assert "n/New.java:1:" in problems[0]
    assert "AwsArnUtils" in problems[0]


def test_check_fails_growth_and_lists_every_occurrence(tmp_path):
    findings = findings_for(tmp_path, {
        "a/A.java": 'class A {\n String a = "arn:aws:s3:::b";\n String c = "arn:aws:s3:::d";\n}\n',
    })
    baseline = Counter({("arn-literal", "a/A.java"): 1})
    problems = p.check(findings, baseline)
    assert len(problems) == 1
    assert "arn-literal grew from 1 to 2" in problems[0]
    assert "a/A.java:2:" in problems[0] and "a/A.java:3:" in problems[0]


def test_check_fails_a_drop_until_the_baseline_is_regenerated(tmp_path):
    findings = findings_for(tmp_path, {"a/A.java": 'class A { String a = "clean"; }\n'})
    baseline = Counter({("arn-literal", "a/A.java"): 3})
    problems = p.check(findings, baseline)
    assert len(problems) == 1
    assert "dropped from 3 to 0" in problems[0]
    assert "make partition-baseline" in problems[0]


def test_check_ignores_report_only_drift(tmp_path):
    findings = findings_for(tmp_path, {"a/A.java": 'class A { String r = "us-east-1"; String z = "us-west-2"; }\n'})
    assert p.check(findings, Counter()) == []


def test_check_treats_an_escaped_literal_as_absent(tmp_path):
    findings = findings_for(tmp_path, {
        "a/A.java": 'class A { String a = "arn:aws:s3:::b"; // partition-literal: fixture\n}\n',
    })
    assert p.check(findings, Counter()) == []


# --------------------------------------------------------------------------- #
# Audit and CLI
# --------------------------------------------------------------------------- #
def test_package_of_groups_by_service_and_falls_back_to_the_parent():
    assert p.package_of("src/main/java/io/github/hectorvent/floci/services/s3/S3Service.java") == "services/s3"
    assert p.package_of("src/main/java/io/github/hectorvent/floci/core/common/AwsArnUtils.java") == "core/common"
    assert p.package_of("src/main/java/io/github/hectorvent/floci/config/EmulatorConfig.java") == "config"
    assert p.package_of("src/main/java/io/github/hectorvent/floci/services/s3/model/Bucket.java") == "services/s3"
    assert p.package_of("elsewhere/x/Y.java") == "elsewhere/x"


def test_format_audit_lists_totals_escapes_and_allowlist_hits(tmp_path):
    write_java(tmp_path, "a/A.java", (
        'class A {\n'
        ' String a = "arn:aws:s3:::b";\n'
        ' String e = "arn:aws:s3:::c"; // partition-literal: fixture value\n'
        ' String g = "http://acs.amazonaws.com/groups/global/AllUsers";\n'
        '}\n'
    ))
    rules = [p.AllowRule("pattern", r"acs\.amazonaws\.com/groups/", "canned ACL")]
    findings = p.collect_findings(tmp_path, rules)
    report = p.format_audit(findings, Counter({("arn-literal", "a/A.java"): 4}))
    assert "escapes (// partition-literal: <reason>): 1" in report
    assert "fixture value" in report
    assert "allow-listed occurrences: 1" in report
    assert "arn-literal 4 -> 1" in report


def test_cli_check_and_write_baseline(tmp_path, capsys):
    write_java(tmp_path / "src", "a/A.java", 'class A { String a = "arn:aws:s3:::b"; }\n')
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text("files: []\npatterns: []\n")
    baseline = tmp_path / "baseline.tsv"
    common = ["--source", str(tmp_path / "src"), "--allowlist", str(allowlist), "--baseline", str(baseline)]

    assert p.main(["--check"] + common) == 1
    assert "no baseline entry" in capsys.readouterr().out

    assert p.main(["--write-baseline"] + common) == 0
    assert "1 gated literal(s) across 1 file(s)" in capsys.readouterr().out

    assert p.main(["--check"] + common) == 0
    assert "match baseline.tsv (1 gated occurrence(s) remaining)" in capsys.readouterr().out

    assert p.main(["--audit"] + common) == 0
    out = capsys.readouterr().out
    assert "arn-literal" in out and "total" in out
