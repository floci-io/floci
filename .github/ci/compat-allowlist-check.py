#!/usr/bin/env python3
"""Compare a compat suite's JUnit results with an allowlist of known failures.

Usage: compat-allowlist-check.py <results-dir> <allowlist> [--test-sources <dir>]

Reads every TEST-*.xml under <results-dir> and collects the test cases that
failed or errored. The allowlist names the failures that are known and
accepted, one per line: `Class#method` for one test or `Class` for every test
in a class, by simple class name (no package). `#`-comments and blank lines
are ignored. A missing allowlist file is an empty one.

Fails when:
  - a test fails that the allowlist does not name (a regression), or
  - an allowlist entry matches no failing test (the gap is closed: delete the
    line, so the list only ever shrinks), or
  - no results were found (the suite did not run), or
  - with --test-sources, there are fewer result files than test classes in that
    source tree (the suite stopped partway: Maven exits non-zero for allowlisted
    failures too, so its status cannot tell the two apart).
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TEST_ANNOTATION = re.compile(r'@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b')


def load_allowlist(path):
    if not Path(path).exists():
        return []
    # `Class#method` carries a `#`, so a comment is a line starting with `#` or a ` #` suffix.
    entries = []
    for raw in Path(path).read_text().splitlines():
        line = raw.split(' #', 1)[0].strip()
        if line and not line.startswith('#'):
            entries.append(line)
    return entries


def failing_tests(results_dir):
    failed = set()
    files = sorted(Path(results_dir).glob('TEST-*.xml'))
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter('testcase'):
            if case.find('failure') is not None or case.find('error') is not None:
                simple_class = case.get('classname', '').rsplit('.', 1)[-1]
                failed.add(f"{simple_class}#{case.get('name', '')}")
    return files, failed


def test_class_count(source_dir):
    return sum(1 for path in Path(source_dir).rglob('*.java')
               if TEST_ANNOTATION.search(path.read_text(encoding='utf-8')))


def matches(entry, test_id):
    return test_id == entry if '#' in entry else test_id.split('#', 1)[0] == entry


def main(argv):
    parser = argparse.ArgumentParser(description='Compare JUnit results with an allowlist.')
    parser.add_argument('results_dir')
    parser.add_argument('allowlist')
    parser.add_argument('--test-sources', help='test source tree; requires a report per test class')
    args = parser.parse_args(argv[1:])
    files, failed = failing_tests(args.results_dir)
    if not files:
        print(f'::error::No TEST-*.xml results under {args.results_dir}: the suite did not run.')
        return 1
    if args.test_sources:
        expected = test_class_count(args.test_sources)
        if len(files) < expected:
            print(f'::error::{len(files)} result files for {expected} test classes: the suite stopped partway.')
            return 1
    allowlist = load_allowlist(args.allowlist)

    unexpected = sorted(t for t in failed if not any(matches(e, t) for e in allowlist))
    stale = [e for e in allowlist if not any(matches(e, t) for t in failed)]
    expected = sorted(t for t in failed if t not in unexpected)

    print(f'{len(files)} result files, {len(failed)} failing tests, '
          f'{len(expected)} allowlisted, {len(allowlist)} allowlist entries.')
    for test_id in unexpected:
        print(f'::error::Not in the allowlist: {test_id}')
    for entry in stale:
        print(f'::error::Allowlist entry no longer fails, delete it: {entry}')
    return 1 if unexpected or stale else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
