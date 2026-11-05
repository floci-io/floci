#!/usr/bin/env python3
"""Read the answers AWS already gave out of LocalStack's AWS-validated snapshots.

LocalStack records what a test's AWS calls returned in a `<test>.snapshot.json` beside the test
that made them, and a test marked `@markers.aws.validated` ran against a real account, so what its
snapshot carries is a recording rather than an emulator's answer. Several of the AWS readings
recorded in `docs/services/iam.md` and `docs/services/sts.md` were settled one at a time by
reading those files by hand: the two IAM constraint messages that said IAM leaves the offending
value out, the enum set `UpdateAccessKey` names, and the STS message for an invalid parameter.
This asks the whole file at once instead, and keeps answering for questions nobody has written
down yet.

What it prints, per recording: the service, the test, its marker, the date LocalStack recorded it,
the path inside the recorded content, and the error code, HTTP status and message. A `--grep` adds
every other string that carries a text, which is how a question that is not about an error code
gets asked.

A marker other than `validated` is not evidence of AWS behaviour, so those tests are left out
unless `--all-markers` asks for them, and then each line says which marker it carries.

Run it against a checkout, which this repository does not vendor:

    git clone --depth 1 https://github.com/localstack/localstack ../localstack
    python3 tools/aws-capture/localstack/mine_snapshot_messages.py --localstack ../localstack
    ... --services iam,sts,logs          # the default is iam,sts
    ... --services all                   # every service that carries a snapshot
    ... --grep "satisfy constraint"      # only the recordings carrying a text
    ... --tsv                            # one row per line, for pasting into an issue
"""

from __future__ import annotations

import argparse
import ast
import json
import sys
from pathlib import Path

DEFAULT_SERVICES = ("iam", "sts")

# Every service directory that carries a snapshot, for a question no one service owns.
ALL = "all"

TESTS = Path("tests/aws/services")

# Only `validated` ran against a real account; the others are an emulator's own answer, a test
# that needs a setup step by hand, or one nobody classified.
VALIDATED = "validated"


# A recorded message carries a newline or a tab often enough to matter: 53 of the messages in the
# snapshots this was written against do. Written through as they are, one row becomes several, or
# gains a column, so the promise of a row per recording is kept by escaping them instead.
TSV_ESCAPES = {"\t": "\\t", "\r": "\\r", "\n": "\\n"}


def tsv_field(value: str | None) -> str:
    """`value` as one TSV field, with anything that would end the row or the field escaped."""
    text = value or ""
    for character, escaped in TSV_ESCAPES.items():
        text = text.replace(character, escaped)
    return text


class Recording:
    """One answer a snapshot carries, with everything needed to cite it."""

    def __init__(self, service: str, test: str, marker: str, recorded: str, path: str,
                 code: str | None, status: str | None, message: str | None):
        self.service = service
        self.test = test
        self.marker = marker
        self.recorded = recorded
        self.path = path
        self.code = code
        self.status = status
        self.message = message

    def as_tsv(self) -> str:
        return "\t".join(tsv_field(field) for field in (
            self.service, self.marker, self.recorded, self.test, self.path,
            self.code, self.status, self.message))

    def as_text(self) -> str:
        head = f"{self.service} {self.marker} {self.recorded} {self.test}{self.path}"
        answer = " ".join(part for part in (self.code, self.status) if part)
        return f"{head}\n    {answer}{': ' if answer and self.message else ''}{self.message or ''}"


def marker_of(decorator: ast.expr) -> str | None:
    """The marker a decorator carries, for `@markers.aws.<name>` and its called form."""
    if isinstance(decorator, ast.Call):
        decorator = decorator.func
    aws = getattr(decorator, "value", None)
    if (isinstance(decorator, ast.Attribute) and isinstance(aws, ast.Attribute)
            and aws.attr == "aws" and isinstance(aws.value, ast.Name) and aws.value.id == "markers"):
        return decorator.attr
    return None


def markers(source: str) -> dict[str, str]:
    """Every test in `source` with the marker it carries, keyed as a snapshot names it.

    A test inside a class is keyed `Class::name`, one at module level by its own name, and a name
    the file carries exactly once is keyed both ways: a snapshot key sometimes names no class for a
    test that now sits in one, and six recordings in the snapshots this was written against are
    read only because the bare name answers for them. It answers only while nothing else in the
    file answers to it too. LocalStack carries 20 names defined in more than one class, and keying
    those bare would let one class's `validated` marker stand for another class's unmarked test,
    which would read as AWS evidence; no recording in that tree does so today, and the rule is what
    keeps it that way.

    The decorators are read off the parsed file rather than matched line by line, because a
    parametrized test stacks several and `@pytest.mark.parametrize` spans as many lines as it has
    cases. A class's own marker carries to the tests inside it, and a test's own wins over it. A
    test with no marker at all is absent rather than unmarked, so a caller can tell a test
    LocalStack never classified from one this does not know about."""
    found: dict[str, str] = {}

    def read(node: ast.AST, holder: str | None, inherited: str | None) -> None:
        for child in ast.iter_child_nodes(node):
            own = next((marker for marker in map(marker_of, getattr(child, "decorator_list", ()))
                        if marker), None)
            if isinstance(child, ast.ClassDef):
                read(child, child.name, own or inherited)
            elif isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                marker = own or inherited
                if child.name.startswith("test_"):
                    bare[child.name] = bare.get(child.name, 0) + 1
                    if marker:
                        found[f"{holder}::{child.name}" if holder else child.name] = marker
                        once[child.name] = marker

    bare: dict[str, int] = {}
    once: dict[str, str] = {}
    read(ast.parse(source), None, None)
    for name, marker in once.items():
        if bare[name] == 1:
            found.setdefault(name, marker)
    return found


def nodes(node: object, path: str = ""):
    """Every value in `node`, itself included, with the path that reaches it.

    The path is a label for the reader rather than a lookup key: an answer is read off the object
    that holds it, so an error nested in a list keeps its message and status, and a key carrying a
    dot of its own cannot be mistaken for two steps."""
    yield path, node
    if isinstance(node, dict):
        for key, value in node.items():
            yield from nodes(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from nodes(value, f"{path}[{index}]")


def recordings(service: str, snapshot: dict, test_markers: dict[str, str],
               grep: list[str], all_markers: bool) -> list[Recording]:
    """The answers `snapshot` carries, in the order the file lists its tests.

    A recording is reported under the key's own tail, `test_iam.py::TestOne::test_x[case]`, so two
    classes carrying the same test name stay two tests in the output and in the count: 18 of the
    tests in the snapshots this was written against would otherwise collapse onto another's name.
    The tail is cut at the file rather than at the last slash, because a parameter carries a slash
    of its own often enough to matter, 33 keys in that tree, and cutting there leaves `name]` where
    a test should be. The marker is looked up by the class and the bare name, which is what
    `markers` keys."""
    found = []
    for key, entry in snapshot.items():
        parts = key.split("::")
        test = "::".join([parts[0].rsplit("/", 1)[-1]] + parts[1:])
        name = parts[-1].split("[")[0]
        qualified = f"{parts[-2]}::{name}" if len(parts) > 2 else name
        marker = test_markers.get(qualified) or test_markers.get(name) or "unknown"
        if marker != VALIDATED and not all_markers:
            continue
        recorded = str(entry.get("recorded-date", ""))
        for path, value in nodes(entry.get("recorded-content", {})):
            error = value.get("Error") if isinstance(value, dict) else None
            if isinstance(error, dict) and "Code" in error:
                metadata = value.get("ResponseMetadata")
                status = metadata.get("HTTPStatusCode") if isinstance(metadata, dict) else None
                found.append(Recording(
                    service, test, marker, recorded, path, str(error["Code"]),
                    None if status is None else str(status), error.get("Message")))
            elif grep and isinstance(value, str) and any(text in value for text in grep):
                # The code and the message of an answer are reported as the answer above rather
                # than again as a text match of their own.
                if not (path.endswith(".Error.Message") or path.endswith(".Error.Code")):
                    found.append(Recording(service, test, marker, recorded, path,
                                           None, None, value))
    return found


def service_of(snapshot: Path) -> str:
    """The service a snapshot belongs to: the directory under `tests/aws/services`.

    A snapshot sits beside its test, which may be a directory or two below the service's own,
    `cloudformation/engine/test_conditions.snapshot.json` and
    `route53/resource_providers/test_aws_route53_recordset.snapshot.json` among them, so the
    immediate parent is the wrong label for a third of the files. A path given on its own, outside
    a checkout, has no such root to read, and its parent is the best that can be said."""
    parts = snapshot.resolve().parts
    for index in range(len(parts) - 1, 0, -1):
        if parts[index - 1] == "services" and parts[index - 2] == "aws" and parts[index - 3] == "tests":
            return parts[index]
    return snapshot.parent.name


def mine(snapshots: list[Path], grep: list[str], all_markers: bool) -> tuple[list[Recording], list[str]]:
    """Every recording the given snapshot files carry, and a note per file that cannot be read."""
    found, notes = [], []
    for snapshot in sorted(snapshots):
        source = snapshot.with_name(snapshot.name.replace(".snapshot.json", ".py"))
        if not source.exists():
            notes.append(f"{snapshot}: no test file beside it, so no marker says whether these "
                         f"recordings came from AWS")
            continue
        found.extend(recordings(service_of(snapshot),
                                json.loads(snapshot.read_text(encoding="utf-8")),
                                markers(source.read_text(encoding="utf-8")), grep, all_markers))
    return found, notes


def snapshots_in(localstack: Path, services: list[str]) -> tuple[list[Path], list[str]]:
    """The snapshot files for `services` under a LocalStack checkout, and a note per empty one.

    The search goes all the way down, because a snapshot sits beside its test rather than at the
    service's own door: 137 of the files in the tree this was written against are a directory or
    two below it, carrying 1505 recordings, and a search one level deep reads none of them.

    `all` is every service directory that carries one, which is how a question that is not about
    one service gets asked: a wording Smithy writes rather than a service does is answered by any
    recording of it, wherever it sits."""
    found, notes = [], []
    if services == [ALL]:
        files = sorted((localstack / TESTS).glob("*/**/*.snapshot.json"))
        if not files:
            return [], [f"{localstack / TESTS} carries no service with a snapshot file: this is "
                        f"not a LocalStack checkout, or it was cloned without those paths"]
        return files, notes
    for service in services:
        directory = localstack / TESTS / service
        if not directory.is_dir():
            notes.append(f"{directory} is not a directory: LocalStack carries no tests for "
                         f"{service}, or this is not a LocalStack checkout")
            continue
        files = sorted(directory.glob("**/*.snapshot.json"))
        if not files:
            notes.append(f"{directory} carries no snapshot file")
        found.extend(files)
    return found, notes


def render(found: list[Recording], tsv: bool) -> str:
    if tsv:
        header = "# service\tmarker\trecorded\ttest\tpath\tcode\tstatus\tmessage\n"
        return header + "".join(one.as_tsv() + "\n" for one in found)
    return "\n".join(one.as_text() for one in found) + ("\n" if found else "")


def summary(found: list[Recording]) -> str:
    codes: dict[str, int] = {}
    for one in found:
        if one.code:
            codes[one.code] = codes.get(one.code, 0) + 1
    tests = len({(one.service, one.test) for one in found})
    counted = ", ".join(f"{code} {count}" for code, count in sorted(codes.items()))
    return (f"{len(found)} recording(s) over {tests} test(s)"
            + (f" / codes: {counted}" if counted else ""))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--localstack", type=Path,
                        help="a LocalStack checkout to read tests/aws/services from")
    parser.add_argument("--snapshot", type=Path, action="append", default=[], metavar="FILE",
                        help="one snapshot file, instead of a checkout; repeatable")
    parser.add_argument("--services", default=",".join(DEFAULT_SERVICES),
                        help=f"comma-separated service directories, or all "
                             f"(default {','.join(DEFAULT_SERVICES)})")
    parser.add_argument("--grep", action="append", default=[], metavar="TEXT",
                        help="also print every recorded string carrying TEXT; repeatable")
    parser.add_argument("--all-markers", action="store_true",
                        help="include tests that never ran against AWS, each line saying so")
    parser.add_argument("--tsv", action="store_true", help="one row per line, tab separated")
    args = parser.parse_args(argv)

    if not args.localstack and not args.snapshot:
        parser.error("pass --localstack <checkout> or --snapshot <file>")

    files, notes = list(args.snapshot), []
    if args.localstack:
        found, found_notes = snapshots_in(args.localstack, args.services.split(","))
        files.extend(found)
        notes.extend(found_notes)
    if not files:
        for note in notes:
            print(f"note: {note}", file=sys.stderr)
        print("error: no snapshot file to read", file=sys.stderr)
        return 1

    recorded, read_notes = mine(files, args.grep, args.all_markers)
    for note in notes + read_notes:
        print(f"note: {note}", file=sys.stderr)
    print(render(recorded, args.tsv), end="")
    print(f"# {summary(recorded)}" if args.tsv else summary(recorded))
    return 0


if __name__ == "__main__":
    sys.exit(main())
