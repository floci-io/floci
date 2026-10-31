"""Tests for rebase_across_rename.sh, against a scratch repository that lands a real cut.

Run with: pytest tools/rename -q  (or: make rename-test)
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

import rename_package as r

TOOL_DIR = Path(__file__).resolve().parent
SCRIPT = TOOL_DIR / "rebase_across_rename.sh"

OLD = "io.github.hectorvent.floci"
OLD_PATH = "io/github/hectorvent/floci"
NEW_PATH = "io/floci/aws"

SQS = f"src/main/java/{OLD_PATH}/services/sqs/SqsService.java"
SNS = f"src/main/java/{OLD_PATH}/services/sns/SnsService.java"
EXCEPTION = f"src/main/java/{OLD_PATH}/core/common/AwsException.java"


def moved(path: str) -> str:
    return path.replace(OLD_PATH, NEW_PATH)


def sqs_service(answer: str, extra: str = "") -> str:
    return (
        f"package {OLD}.services.sqs;\n"
        "\n"
        f"import {OLD}.core.common.AwsException;\n"
        "\n"
        "class SqsService {\n"
        f"    int answer() {{ return {answer}; }}\n"
        f"{extra}"
        "}\n"
    )


ENV = {
    **os.environ,
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "main",
    "GIT_AUTHOR_EMAIL": "main@example.com",
    "GIT_COMMITTER_NAME": "main",
    "GIT_COMMITTER_EMAIL": "main@example.com",
}


def git(root: Path, *args: str, author: str = "main", check: bool = True) -> str:
    env = {**ENV, "GIT_AUTHOR_NAME": author, "GIT_AUTHOR_EMAIL": f"{author}@example.com"}
    return subprocess.run(["git", *args], cwd=root, env=env, check=check, capture_output=True, text=True).stdout


def write(root: Path, path: str, content: str) -> None:
    file = root / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content, encoding="utf-8")


def commit(root: Path, message: str, author: str = "main") -> str:
    git(root, "add", "--all")
    git(root, "commit", "-q", "-m", message, author=author)
    return git(root, "rev-parse", "HEAD").strip()


def run_script(root: Path, *args: str, cwd: Path | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["bash", str(SCRIPT), *args], cwd=cwd or root, env=ENV, capture_output=True, text=True)


def land_cut(root: Path) -> None:
    r.main(["--root", str(root), "apply", "--moves-only"])
    commit(root, "chore: move the sources to io.floci.aws")
    r.main(["--root", str(root), "apply", "--rewrite-only"])
    commit(root, "chore: rename the package to io.floci.aws")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """main forks feature (two commits), moves on, lands the cut, then moves on again."""
    root = tmp_path / "repo"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    (root / "tools/rename").mkdir(parents=True)
    shutil.copy(TOOL_DIR / "rename_package.py", root / "tools/rename/rename_package.py")
    write(root, EXCEPTION, f"package {OLD}.core.common;\n\npublic class AwsException {{\n}}\n")
    write(root, SQS, sqs_service("1"))
    write(root, "pom.xml", "<project><groupId>io.github.hectorvent</groupId><artifactId>floci</artifactId></project>\n")
    commit(root, "base")

    git(root, "checkout", "-q", "-b", "feature")
    write(root, SQS, sqs_service("1", "    void purge() { throw new AwsException(); }\n"))
    commit(root, "feat(sqs): add purge\n\nSigned-off-by: contributor <contributor@example.com>", author="contributor")
    write(root, SNS, f"package {OLD}.services.sns;\n\nimport {OLD}.core.common.AwsException;\n\nclass SnsService {{\n}}\n")
    commit(root, "feat(sns): add the service\n\nSigned-off-by: contributor <contributor@example.com>", author="contributor")

    git(root, "checkout", "-q", "main")
    write(root, EXCEPTION, f"package {OLD}.core.common;\n\npublic class AwsException {{\n    String code;\n}}\n")
    commit(root, "fix(core): carry the error code")
    land_cut(root)
    write(root, "README.md", "post-cut change\n")
    commit(root, "docs: add a readme")
    return root


def test_branch_lands_on_the_renamed_main_with_only_its_own_changes(repo: Path):
    old_tip = git(repo, "rev-parse", "feature").strip()

    result = run_script(repo, "feature", "main")

    assert result.returncode == 0, result.stderr
    assert git(repo, "rev-parse", "feature^").strip() == git(repo, "rev-parse", "main").strip()
    assert git(repo, "rev-parse", "feature-before-rename").strip() == old_tip
    assert sorted(git(repo, "diff", "--name-only", "main", "feature").split()) == sorted([moved(SNS), moved(SQS)])
    sqs = git(repo, "show", f"feature:{moved(SQS)}")
    assert sqs.startswith("package io.floci.aws.services.sqs;\n\nimport io.floci.aws.core.common.AwsException;")
    assert "void purge()" in sqs
    assert "String code;" in git(repo, "show", f"feature:{moved(EXCEPTION)}")
    assert git(repo, "grep", "-l", "io.github.hectorvent", "feature", "--", ".", ":!tools/rename", check=False) == ""
    message = git(repo, "log", "-1", "--format=%B", "feature")
    assert message.index("feat(sqs): add purge") < message.index("feat(sns): add the service")
    assert message.count("Signed-off-by: contributor <contributor@example.com>") == 2
    assert git(repo, "log", "-1", "--format=%an", "feature").strip() == "contributor"
    assert "rebase-across-rename" not in git(repo, "worktree", "list")


def test_a_checked_out_branch_moves_in_place(repo: Path):
    git(repo, "checkout", "-q", "feature")

    result = run_script(repo, "feature", "main")

    assert result.returncode == 0, result.stderr
    assert git(repo, "symbolic-ref", "--short", "HEAD").strip() == "feature"
    assert git(repo, "status", "--porcelain") == ""
    assert (repo / moved(SNS)).is_file()
    assert not (repo / SNS).exists()


def test_runs_from_a_subdirectory_of_the_checkout(repo: Path):
    result = run_script(repo, "feature", "main", cwd=repo / "src/main")

    assert result.returncode == 0, result.stderr
    assert git(repo, "rev-parse", "feature^").strip() == git(repo, "rev-parse", "main").strip()
    assert sorted(git(repo, "diff", "--name-only", "main", "feature").split()) == sorted([moved(SNS), moved(SQS)])


def test_a_real_conflict_stops_and_keeps_the_scratch_worktree(repo: Path):
    git(repo, "checkout", "-q", "feature")
    write(repo, SQS, sqs_service("2", "    void purge() { throw new AwsException(); }\n"))
    commit(repo, "fix(sqs): answer two", author="contributor")
    git(repo, "checkout", "-q", "main")
    write(repo, moved(SQS), sqs_service("3").replace(OLD, "io.floci.aws"))
    commit(repo, "fix(sqs): answer three")
    old_tip = git(repo, "rev-parse", "feature").strip()

    result = run_script(repo, "feature", "main")

    assert result.returncode == 1
    assert moved(SQS) in result.stderr
    assert "git cherry-pick --continue" in result.stderr
    assert git(repo, "rev-parse", "feature").strip() == old_tip
    assert git(repo, "rev-parse", "feature-before-rename").strip() == old_tip
    scratch = [line.split()[0] for line in git(repo, "worktree", "list").splitlines() if "rebase-across-rename" in line]
    assert len(scratch) == 1 and Path(scratch[0]).is_dir()
    git(repo, "worktree", "remove", "--force", scratch[0])


def test_a_branch_already_merged_upstream_is_left_alone(tmp_path: Path):
    root = tmp_path / "repo"
    root.mkdir()
    git(root, "init", "-q", "-b", "main")
    (root / "tools/rename").mkdir(parents=True)
    shutil.copy(TOOL_DIR / "rename_package.py", root / "tools/rename/rename_package.py")
    write(root, SQS, sqs_service("1"))
    commit(root, "base")
    git(root, "checkout", "-q", "-b", "feature")
    write(root, SQS, sqs_service("2"))
    commit(root, "fix(sqs): answer two", author="contributor")
    git(root, "checkout", "-q", "main")
    write(root, SQS, sqs_service("2"))
    commit(root, "fix(sqs): answer two (#1)")
    land_cut(root)
    old_tip = git(root, "rev-parse", "feature").strip()

    result = run_script(root, "feature", "main")

    assert result.returncode == 1
    assert "already in main" in result.stderr
    assert git(root, "rev-parse", "feature").strip() == old_tip
    assert git(root, "branch", "--list", "feature-before-rename") == ""
    assert "rebase-across-rename" not in git(root, "worktree", "list")


def test_refuses_a_target_without_the_rename(repo: Path):
    before_cut = git(repo, "log", "-1", "--format=%H", "-F", "--grep=fix(core): carry the error code", "main").strip()

    result = run_script(repo, "feature", before_cut)

    assert result.returncode == 1
    assert "the rename has not landed there" in result.stderr


def test_refuses_a_branch_that_already_crossed(repo: Path):
    assert run_script(repo, "feature", "main").returncode == 0

    result = run_script(repo, "feature", "main")

    assert result.returncode == 1
    assert "already contains the rename" in result.stderr


def test_refuses_a_branch_checked_out_in_another_worktree(repo: Path, tmp_path: Path):
    git(repo, "worktree", "add", "-q", str(tmp_path / "other"), "feature")

    result = run_script(repo, "feature", "main")

    assert result.returncode == 1
    assert "checked out in another worktree" in result.stderr
