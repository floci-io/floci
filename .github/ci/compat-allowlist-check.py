#!/usr/bin/env python3
"""Compare a compat suite's JUnit results with an allowlist of known failures.

Usage: compat-allowlist-check.py <results-dir> <allowlist>

Reads every TEST-*.xml under <results-dir> and collects the test cases that
failed or errored. The allowlist names the failures that are known and
accepted, one per line: `Class#method` for one test or `Class` for every test
in a class, by simple class name (no package). `#`-comments and blank lines
are ignored. A missing allowlist file is an empty one.

Fails when:
  - a test fails that the allowlist does not name (a regression), or
  - an allowlist entry matches no failing test (the gap is closed: delete the
    line, so the list only ever shrinks), or
  - no results were found (the suite did not run).
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


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


def matches(entry, test_id):
    return test_id == entry if '#' in entry else test_id.split('#', 1)[0] == entry


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    files, failed = failing_tests(argv[1])
    if not files:
        print(f'::error::No TEST-*.xml results under {argv[1]}: the suite did not run.')
        return 1
    allowlist = load_allowlist(argv[2])

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
