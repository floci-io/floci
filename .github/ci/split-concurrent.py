#!/usr/bin/env python3
"""Split a shard's test classes into the ones that boot Quarkus and the ones that do not.

Usage: split-concurrent.py <shard.txt> <quarkus-out> <plain-out>

A @QuarkusTest class shares the one emulator the test JVM starts, so classes of that kind run one at a
time. Every other class is a plain JUnit class with its own fixtures, which ci.yml runs four at a time
in a separate Surefire pass. A class counts as Quarkus when its source carries @QuarkusTest or it
extends, at any depth, a class in src/test/java that does: JUnit finds @ExtendWith on a superclass.

Both outputs keep the input's order. Either may be empty; ci.yml skips the pass for an empty one,
since an empty includesFile makes Surefire run the whole suite.
Exit 2 on bad arguments or an unreadable input.
"""
import os
import re
import sys

QUARKUS = re.compile(r'^\s*@QuarkusTest\b', re.M)
EXTENDS = re.compile(r'^\s*(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+\w+(?:<[^>]*>)?\s+extends\s+(\w+)', re.M)


def sources(test_root):
    """Map simple class name -> source text for every top-level file in src/test/java."""
    found = {}
    for dirpath, _, files in os.walk(test_root):
        for name in files:
            if name.endswith('.java'):
                with open(os.path.join(dirpath, name), encoding='utf-8', errors='replace') as f:
                    found[name[:-len('.java')]] = f.read()
    return found


def is_quarkus(name, src_by_name, seen=None):
    seen = seen or set()
    src = src_by_name.get(name)
    if src is None or name in seen:
        return False
    if QUARKUS.search(src):
        return True
    seen.add(name)
    parent = EXTENDS.search(src)
    return bool(parent) and is_quarkus(parent.group(1), src_by_name, seen)


def main():
    if len(sys.argv) != 4:
        print('usage: split-concurrent.py <shard.txt> <quarkus-out> <plain-out>', file=sys.stderr)
        sys.exit(2)
    try:
        with open(sys.argv[1]) as f:
            classes = [line.strip() for line in f if line.strip()]
    except OSError as e:
        print(f'split-concurrent.py: {e}', file=sys.stderr)
        sys.exit(2)
    src_by_name = sources('src/test/java')
    quarkus = [c for c in classes if is_quarkus(c.rsplit('/', 1)[-1], src_by_name)]
    quarkus_set = set(quarkus)
    plain = [c for c in classes if c not in quarkus_set]
    with open(sys.argv[2], 'w') as f:
        f.writelines(c + '\n' for c in quarkus)
    with open(sys.argv[3], 'w') as f:
        f.writelines(c + '\n' for c in plain)
    print(f'{len(quarkus)} Quarkus classes, {len(plain)} plain classes')


if __name__ == '__main__':
    main()
