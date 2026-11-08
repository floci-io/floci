"""Tests for rename_package.

Run with: pytest tools/rename -q  (or: make rename-test)
"""
from __future__ import annotations

import subprocess
from collections import Counter
from pathlib import Path

import pytest

import rename_package as r

OLD = "io.github.hectorvent.floci"
OLD_PATH = "io/github/hectorvent/floci"


def categories(src: str) -> Counter:
    return Counter(hit.category for hit in r.classify_java("A.java", src))


# --------------------------------------------------------------------------- #
# Classification
# --------------------------------------------------------------------------- #
def test_package_and_import_lines_are_their_own_categories():
    src = (
        f"package {OLD}.services.sqs;\n"
        f"import {OLD}.core.common.AwsException;\n"
        f"import static {OLD}.core.common.Util.x;\n"
    )
    assert categories(src) == Counter({"package": 1, "import": 2})


def test_each_inline_reference_counts_even_two_on_one_line():
    src = f"{OLD}.a.Tag t = new {OLD}.a.Tag();\n"
    assert categories(src) == Counter({"code": 2})


def test_string_literals_in_both_forms_are_strings():
    src = (
        f'String c = "{OLD}.services.appsync.graphql.ReturnDirective";\n'
        f'Path p = Path.of("src/main/java/{OLD_PATH}/services");\n'
        f'@MethodSource("{OLD}.testing.PartitionMatrix#cases")\n'
    )
    assert categories(src) == Counter({"string": 3})


def test_comments_javadoc_and_text_blocks_are_comments():
    src = (
        f"// see {OLD}.x.Y\n"
        f"/** {{@link {OLD}.x.Y}} */\n"
        f"/* {OLD}.x.Y\n   {OLD}.x.Z */\n"
        f'String t = """\n    {OLD}.x.Y\n    """;\n'
    )
    assert categories(src) == Counter({"comment": 5})


def test_a_url_in_a_string_does_not_start_a_comment():
    src = f'String u = "https://example.com"; {OLD}.x.Y y = null;\n'
    assert categories(src) == Counter({"code": 1})


def test_char_literal_quote_does_not_open_a_string():
    src = f"char q = '\"'; {OLD}.x.Y y = null;\n"
    assert categories(src) == Counter({"code": 1})


def test_reported_line_numbers_are_one_based():
    src = f"package a;\n\nclass A {{ {OLD}.x.Y y; }}\n"
    assert [(hit.line, hit.category) for hit in r.classify_java("A.java", src)] == [(3, "code")]


def test_non_java_files_count_rewritten_forms_and_flag_the_rest():
    pom = "<groupId>io.github.hectorvent</groupId>\n<p>io.github.hectorvent.floci.arn.x</p>\n"
    assert Counter(h.category for h in r.classify_other("pom.xml", pom)) == Counter({"non-java": 2})
    readme = "see io.github.hectorvent.other and io/github/hectorvent/x\n"
    assert Counter(h.category for h in r.classify_other("README.md", readme)) == Counter({"other": 2})


def test_quarkus_env_var_form_is_rewritten_and_its_group_alone_is_flagged():
    docs = (
        "QUARKUS_LOG_CATEGORY__IO_GITHUB_HECTORVENT_FLOCI_SERVICES_SQS__LEVEL=TRACE\n"
        "QUARKUS_LOG_CATEGORY__IO_GITHUB_HECTORVENT_OTHER__LEVEL=TRACE\n"
    )
    assert [(h.line, h.category) for h in r.classify_other("docs/a.md", docs)] == [(1, "non-java"), (2, "other")]


def test_old_group_alone_in_java_is_other():
    assert categories('String g = "io.github.hectorvent";\n') == Counter({"other": 1})


def test_moved_path_covers_both_trees_and_the_native_image_dir():
    assert r.moved_path(f"src/main/java/{OLD_PATH}/a/A.java") == "src/main/java/io/floci/aws/a/A.java"
    assert r.moved_path(f"compatibility-tests/sdk-test-java/src/test/java/{OLD_PATH}/compat/C.java") == \
        "compatibility-tests/sdk-test-java/src/test/java/io/floci/aws/compat/C.java"
    assert r.moved_path("src/main/resources/META-INF/native-image/io.github.hectorvent/floci/reflect-config.json") \
        == "src/main/resources/META-INF/native-image/io.floci/floci/reflect-config.json"
    assert r.moved_path("src/main/java/com/floci/test/T.java") is None
    assert r.moved_path(f"docs/{OLD_PATH}.md") is None


# --------------------------------------------------------------------------- #
# End to end on a throwaway repository
# --------------------------------------------------------------------------- #
FIXTURE = {
    f"src/main/java/{OLD_PATH}/services/sqs/SqsService.java": (
        f"package {OLD}.services.sqs;\n\n"
        f"import {OLD}.core.common.AwsException;\n\n"
        f"/** Uses {{@link {OLD}.core.common.AwsException}}. */\n"
        f"public class SqsService {{\n"
        f'    static final String NAME = "{OLD}.services.sqs.SqsService";\n'
        f"    {OLD}.services.sns.model.Tag tag;\n"
        f"}}\n"
    ),
    f"src/main/java/{OLD_PATH}/core/common/AwsException.java": f"package {OLD}.core.common;\nclass AwsException {{}}\n",
    f"src/test/java/{OLD_PATH}/services/sqs/SqsServiceTest.java": (
        f"package {OLD}.services.sqs;\n"
        f'class SqsServiceTest {{ java.nio.file.Path p = java.nio.file.Path.of("src/main/java/{OLD_PATH}"); }}\n'
    ),
    f"compatibility-tests/sdk-test-java/src/test/java/{OLD_PATH}/compat/C.java": f"package {OLD}.compat;\nclass C {{}}\n",
    "src/main/resources/META-INF/native-image/io.github.hectorvent/floci/reflect-config.json": "[]\n",
    "src/main/resources/META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource":
        f"{OLD}.config.TlsConfigSource\n",
    "src/main/resources/application.yml": f"args: --initialize-at-run-time={OLD}.services.x.Y\n",
    "pom.xml": (
        "<project><groupId>io.github.hectorvent</groupId><artifactId>floci</artifactId>\n"
        f"<x>{OLD}.arn.fail-on-blank-region</x></project>\n"
    ),
    "tools/docs/services.yaml": f"path: src/main/java/{OLD_PATH}/services/sqs/SqsService.java\n",
    "docs/logging.md": "QUARKUS_LOG_CATEGORY__IO_GITHUB_HECTORVENT_FLOCI_SERVICES_SQS__LEVEL=TRACE\n",
    "CHANGELOG.md": f"- moved {OLD}\n",
    "tools/rename/notes.txt": f"the old package is {OLD}\n",
    "src/main/java/com/floci/test/Untouched.java": "package com.floci.test;\n",
}


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    for path, content in FIXTURE.items():
        file = tmp_path / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding="utf-8")
    git(tmp_path, "init", "-q")
    git(tmp_path, "add", "-A")
    git(tmp_path, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
        "commit", "-q", "-m", "fixture")
    return tmp_path


def test_report_counts_the_fixture(repo: Path, capsys):
    hits = r.scan(repo)
    assert Counter(hit.category for hit in hits) == Counter(
        {"package": 4, "import": 1, "code": 1, "string": 2, "comment": 1, "non-java": 6})
    assert all(not hit.path.startswith(("CHANGELOG.md", "tools/rename/")) for hit in hits)
    r.main(["--root", str(repo), "report", "--files", "3"])
    out = capsys.readouterr().out
    assert "Files the cut moves: 5" in out


def test_moves_only_renames_without_touching_content(repo: Path):
    r.main(["--root", str(repo), "apply", "--moves-only"])
    status = git(repo, "status", "--porcelain")
    assert status and all(line.startswith("R ") for line in status.splitlines()), status
    assert "R  src/main/java/io/github/hectorvent/floci/services/sqs/SqsService.java -> " \
           "src/main/java/io/floci/aws/services/sqs/SqsService.java" in status
    assert "native-image/io.floci/floci/reflect-config.json" in status
    assert f"package {OLD}.services.sqs;" in (repo / "src/main/java/io/floci/aws/services/sqs/SqsService.java").read_text()
    assert not (repo / "src/main/java" / OLD_PATH).exists()


def test_full_apply_leaves_only_the_excluded_files(repo: Path):
    r.main(["--root", str(repo), "apply"])
    assert r.scan(repo) == []
    leftovers = git(repo, "grep", "-il", "-e", "io.github.hectorvent", "-e", "io/github/hectorvent").split()
    assert sorted(leftovers) == ["CHANGELOG.md", "tools/rename/notes.txt"]
    service = (repo / "src/main/java/io/floci/aws/services/sqs/SqsService.java").read_text()
    assert service.startswith("package io.floci.aws.services.sqs;\n\nimport io.floci.aws.core.common.AwsException;")
    assert '"io.floci.aws.services.sqs.SqsService"' in service
    assert "<groupId>io.floci</groupId><artifactId>floci</artifactId>" in (repo / "pom.xml").read_text()
    assert "io.floci.aws.arn.fail-on-blank-region" in (repo / "pom.xml").read_text()
    assert "src/main/java/io/floci/aws/services/sqs" in (repo / "tools/docs/services.yaml").read_text()
    assert (repo / "docs/logging.md").read_text() == "QUARKUS_LOG_CATEGORY__IO_FLOCI_AWS_SERVICES_SQS__LEVEL=TRACE\n"
    assert (repo / "src/main/java/com/floci/test/Untouched.java").read_text() == "package com.floci.test;\n"


def test_rewrite_is_idempotent(repo: Path):
    r.main(["--root", str(repo), "apply"])
    before = git(repo, "status", "--porcelain")
    r.main(["--root", str(repo), "apply", "--rewrite-only"])
    assert git(repo, "status", "--porcelain") == before
