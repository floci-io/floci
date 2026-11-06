#!/usr/bin/env python3
"""Print per-class timings from junit XMLs, slowest first.

Usage: compat-timing-report.py <results-dir>
Also appends the table to GITHUB_STEP_SUMMARY when set. Never fails the job:
bad or absent XMLs produce an empty report, not an error.

Times are summed per test case classname, not read from the testsuite element:
under JUnit parallel execution Surefire writes some test cases into another
class's XML file, but each one still names its own class. Class-level setup
outside a test case is not counted.
"""
from collections import defaultdict
import glob
import os
import sys
import xml.etree.ElementTree as ET

def main():
    d = sys.argv[1] if len(sys.argv) > 1 else 'test-results'
    times = defaultdict(float)
    counts = defaultdict(int)
    for f in glob.glob(os.path.join(d, '*.xml')):
        try:
            root = ET.parse(f).getroot()
        except (ET.ParseError, OSError) as exc:
            print(f'warning: skipping unreadable JUnit XML {f}: {exc}', file=sys.stderr)
            continue
        suites = root.iter('testsuite') if root.tag != 'testsuite' else [root]
        for ts in suites:
            for tc in ts.iter('testcase'):
                name = tc.get('classname') or ts.get('name') or os.path.basename(f)
                try:
                    times[name] += float(tc.get('time') or 0)
                except ValueError as exc:
                    print(f'warning: skipping test case with a non-numeric time in {f}: {exc}',
                          file=sys.stderr)
                    continue
                counts[name] += 1
    rows = [(times[name], counts[name], name) for name in counts]
    rows.sort(reverse=True)
    total_t = sum(r[0] for r in rows)
    total_n = sum(r[1] for r in rows)
    lines = [f'== {len(rows)} classes, {total_n} tests, {total_t / 60:.1f} min total test time',
             '== slowest 25 classes:']
    lines += [f'{t:8.1f}s {n:5d} tests  {name}' for t, n, name in rows[:25]]
    report = '\n'.join(lines)
    print(report)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as fh:
            fh.write('```\n' + report + '\n```\n')

if __name__ == '__main__':
    main()
