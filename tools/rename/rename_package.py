#!/usr/bin/env python3
"""Move Floci's Java package from io.github.hectorvent.floci to io.floci.aws, and measure what is left.

The rename lands as one mechanical cut, prepared by smaller PRs that take out everything a
text rewrite should not have to carry: inline fully-qualified names, hardcoded source paths,
class names written as strings. This tool is both halves of that:

- `report` counts every reference to the old package by where it sits, so each prep PR can
  show its count going down and the cut can be checked for leftovers;
- `apply` performs the cut, in the two commits it is landed as: `--moves-only` renames the
  directories with `git mv` (content untouched, so every file is a 100% rename), then
  `--rewrite-only` rewrites the references in tracked text files.

The scan is pattern based and re-reads the tree on every run, so code merged between now and
the cut is covered without editing this file.

Run from anywhere in the repo:
    python3 tools/rename/rename_package.py report             # counts by category
    python3 tools/rename/rename_package.py report --files 20  # plus the 20 files with most non-import hits
    python3 tools/rename/rename_package.py apply --moves-only
    python3 tools/rename/rename_package.py apply --rewrite-only
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "partition"))
from partition_literals import code_text  # noqa: E402  the same comment/string lexer as the partition gate

REPO_ROOT = Path(__file__).resolve().parents[2]

OLD_PACKAGE = "io.github.hectorvent.floci"
NEW_PACKAGE = "io.floci.aws"
OLD_GROUP = "io.github.hectorvent"
NEW_GROUP = "io.floci"


def _path_form(package: str) -> str:
    return package.replace(".", "/")


# Text rewrites, applied in order to every tracked text file outside EXCLUDED. The package forms
# go first so the groupId rule below only ever sees what is left of the old group.
REWRITES: list[tuple[str, str]] = [
    (OLD_PACKAGE, NEW_PACKAGE),
    (_path_form(OLD_PACKAGE), _path_form(NEW_PACKAGE)),
    # META-INF/native-image/<groupId>/<artifactId>: the artifactId stays floci.
    (f"native-image/{OLD_GROUP}/floci", f"native-image/{NEW_GROUP}/floci"),
    (f"<groupId>{OLD_GROUP}</groupId>", f"<groupId>{NEW_GROUP}</groupId>"),
]

# Directory moves: any tracked path segment run that matches the left side moves to the right.
MOVES: list[tuple[str, str]] = [
    (f"/{_path_form(OLD_PACKAGE)}/", f"/{_path_form(NEW_PACKAGE)}/"),
    (f"/native-image/{OLD_GROUP}/floci/", f"/native-image/{NEW_GROUP}/floci/"),
]

# The changelog records history under the names it had; this tool names the old package on purpose.
EXCLUDED_FILES = {"CHANGELOG.md"}
EXCLUDED_DIRS = ("tools/rename/",)

CATEGORIES = ("package", "import", "code", "string", "comment", "non-java", "other")
CATEGORY_HELP = {
    "package": "package declarations (rewritten by the cut, unavoidable)",
    "import": "import lines (rewritten by the cut, unavoidable)",
    "code": "inline fully-qualified names in code (phase 3 turns the clash-free ones into imports)",
    "string": "inside string literals: class names, source paths, logger names, @MethodSource",
    "comment": "inside comments, javadoc or text blocks",
    "non-java": "tracked non-Java files: build, resources, tools, docs",
    "other": f"the old group {OLD_GROUP} outside the forms the cut rewrites; must be zero before the cut",
}

# Longest form first: an alternation takes the first alternative that matches at a position, so a
# package reference is one hit, never also a group hit.
OCCURRENCE = re.compile(
    "|".join(re.escape(form) for form in (OLD_PACKAGE, _path_form(OLD_PACKAGE), OLD_GROUP, _path_form(OLD_GROUP))))


@dataclass(frozen=True)
class Hit:
    path: str
    line: int
    category: str


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def is_excluded(path: str) -> bool:
    return path in EXCLUDED_FILES or path.startswith(EXCLUDED_DIRS)


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError):
        return None


def _rewritten_by_cut(match: str, text: str, start: int) -> bool:
    """Whether a match of the old group is part of a form REWRITES covers."""
    for old, _new in REWRITES:
        begin = max(0, start - len(old))
        window = text[begin:start + len(old)]
        offset = window.find(old)
        while offset >= 0:
            if begin + offset <= start < begin + offset + len(old):
                return True
            offset = window.find(old, offset + 1)
    return False


def _string_spans(code_line: str) -> list[tuple[int, int]]:
    """Start/end columns of the double-quoted literals on one line of code_text output."""
    spans: list[tuple[int, int]] = []
    i = 0
    while i < len(code_line):
        char = code_line[i]
        if char == "'":
            j = i + 1
            while j < len(code_line) and code_line[j] != "'":
                j += 2 if code_line[j] == "\\" else 1
            i = j + 1
        elif char == '"':
            j = i + 1
            while j < len(code_line) and code_line[j] != '"':
                j += 2 if code_line[j] == "\\" else 1
            spans.append((i, j + 1))
            i = j + 1
        else:
            i += 1
    return spans


def classify_java(path: str, text: str) -> list[Hit]:
    hits: list[Hit] = []
    code = code_text(text)
    lines = text.split("\n")
    code_lines = code.split("\n")
    line_starts = [0]
    for line in lines[:-1]:
        line_starts.append(line_starts[-1] + len(line) + 1)
    for match in OCCURRENCE.finditer(text):
        start = match.start()
        line_index = _line_of(line_starts, start)
        column = start - line_starts[line_index]
        if not _rewritten_by_cut(match.group(), text, start):
            hits.append(Hit(path, line_index + 1, "other"))
            continue
        if code[start] != text[start]:
            category = "comment"
        else:
            stripped = code_lines[line_index].lstrip()
            if stripped.startswith("package "):
                category = "package"
            elif stripped.startswith("import "):
                category = "import"
            elif any(begin < column < end for begin, end in _string_spans(code_lines[line_index])):
                category = "string"
            else:
                category = "code"
        hits.append(Hit(path, line_index + 1, category))
    return hits


def _line_of(line_starts: list[int], offset: int) -> int:
    low, high = 0, len(line_starts) - 1
    while low < high:
        mid = (low + high + 1) // 2
        if line_starts[mid] <= offset:
            low = mid
        else:
            high = mid - 1
    return low


def classify_other(path: str, text: str) -> list[Hit]:
    hits: list[Hit] = []
    for number, line in enumerate(text.split("\n"), start=1):
        for match in OCCURRENCE.finditer(line):
            category = "non-java" if _rewritten_by_cut(match.group(), line, match.start()) else "other"
            hits.append(Hit(path, number, category))
    return hits


def scan(root: Path) -> list[Hit]:
    hits: list[Hit] = []
    for path in tracked_files(root):
        if is_excluded(path):
            continue
        text = read_text(root / path)
        if text is None or not OCCURRENCE.search(text):
            continue
        hits.extend(classify_java(path, text) if path.endswith(".java") else classify_other(path, text))
    return hits


def moved_path(path: str) -> str | None:
    framed = "/" + path
    for old, new in MOVES:
        if old in framed:
            return framed.replace(old, new, 1)[1:]
    return None


def report(root: Path, top_files: int, as_json: bool) -> int:
    hits = scan(root)
    counts = Counter(hit.category for hit in hits)
    files_by_category: dict[str, set[str]] = defaultdict(set)
    for hit in hits:
        files_by_category[hit.category].add(hit.path)
    moves = sum(1 for path in tracked_files(root) if moved_path(path) and not is_excluded(path))
    per_file = Counter(hit.path for hit in hits if hit.category in ("code", "string", "comment", "other"))
    if as_json:
        print(json.dumps({
            "moves": moves,
            "categories": {c: {"occurrences": counts[c], "files": len(files_by_category[c])} for c in CATEGORIES},
            "top_files": per_file.most_common(top_files),
        }, indent=2))
        return 0
    print(f"Files the cut moves: {moves}")
    print(f"{'category':<10} {'hits':>7} {'files':>6}  meaning")
    for category in CATEGORIES:
        print(f"{category:<10} {counts[category]:>7} {len(files_by_category[category]):>6}  {CATEGORY_HELP[category]}")
    if top_files:
        print(f"\nFiles with the most code, string, comment or other hits (top {top_files}):")
        for path, count in per_file.most_common(top_files):
            print(f"{count:>5}  {path}")
    if counts["other"]:
        print("\nOld-group references the cut would not rewrite:")
        for hit in hits:
            if hit.category == "other":
                print(f"  {hit.path}:{hit.line}")
    return 0


def apply_moves(root: Path) -> int:
    targets: dict[str, str] = {}
    for path in tracked_files(root):
        if is_excluded(path):
            continue
        new = moved_path(path)
        if new:
            old_dir, new_dir = _moved_directory(path, new)
            targets[old_dir] = new_dir
    for old_dir, new_dir in sorted(targets.items()):
        (root / new_dir).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "mv", old_dir, new_dir], cwd=root, check=True)
        print(f"moved {old_dir} -> {new_dir}")
    return 0


def _moved_directory(old: str, new: str) -> tuple[str, str]:
    """The shortest directory pair that carries the move, so one git mv covers a whole tree."""
    for old_segment, new_segment in MOVES:
        framed = "/" + old
        index = framed.find(old_segment)
        if index >= 0:
            old_dir = framed[1:index + len(old_segment) - 1]
            new_dir = old_dir[:index - 1] + new_segment[:-1] if index > 0 else new_segment[1:-1]
            return old_dir, new_dir
    raise ValueError(f"{old} does not move")


def apply_rewrites(root: Path) -> int:
    changed = 0
    for path in tracked_files(root):
        if is_excluded(path):
            continue
        file = root / path
        text = read_text(file)
        if text is None:
            continue
        updated = text
        for old, new in REWRITES:
            updated = updated.replace(old, new)
        if updated != text:
            file.write_text(updated, encoding="utf-8")
            changed += 1
    print(f"rewrote {changed} files")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="repository root (default: this checkout)")
    commands = parser.add_subparsers(dest="command", required=True)
    report_parser = commands.add_parser("report", help="count references to the old package")
    report_parser.add_argument("--files", type=int, default=0, help="also list the N files with most non-import hits")
    report_parser.add_argument("--json", action="store_true")
    apply_parser = commands.add_parser("apply", help="perform the rename")
    step = apply_parser.add_mutually_exclusive_group()
    step.add_argument("--moves-only", action="store_true", help="git mv the trees, content untouched")
    step.add_argument("--rewrite-only", action="store_true", help="rewrite references in tracked files")
    args = parser.parse_args(argv)

    root = args.root.resolve()
    if args.command == "report":
        return report(root, args.files, args.json)
    if not args.rewrite_only:
        apply_moves(root)
    if not args.moves_only:
        apply_rewrites(root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
