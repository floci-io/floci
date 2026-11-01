#!/usr/bin/env python3
"""Deterministic group-by-profile test partitioner for CI sharding.

Usage: partition.sh <all-classes.txt> <shard-total> <shard-index> [durations.json]

<all-classes.txt> holds one test class per line in surefire includesFile path
format (io/github/.../FooTest, no extension). Classes sharing a @TestProfile are
assigned to the SAME shard atomically: per-shard Quarkus augmentation cost is
(distinct profiles in shard) x ~5.4s, so splitting a profile group pays its
augmentation once per shard it touches. Profile-less classes fill the remainder.

Deterministic: sorted inputs, greedy least-loaded bin-packing, no RNG/timestamps.
An optional duration file enables measured-cost packing. Three arguments retain
the existing assignment. Invalid arguments or data fail before emitting classes.
"""
import json
import math
import os
import re
import sys

AUGMENTATION_WEIGHT = 10  # legacy class-equivalent weight; preserve assignments
MEASURED_PROFILE_ALLOWANCE_SECONDS = 10.0  # heuristic, not measured startup

def profile_groups(test_root):
    """Map class-path -> profile key from @TestProfile usages in src/test."""
    ann = re.compile(r'@TestProfile\(\s*([A-Za-z0-9_.]+?)(?:\.class)?\s*\)')
    mapping = {}
    for dirpath, _, files in sorted(os.walk(test_root)):
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            try:
                src = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            m = ann.search(src)
            if not m:
                continue
            cls = os.path.relpath(path, test_root)[:-len('.java')]
            # Key on the referenced profile token; identical tokens merge groups,
            # which is always safe (merging groups never splits an augmentation).
            mapping[cls] = m.group(1)
    return mapping

def read_durations(path):
    """Read measured seconds; reject corrupt evidence instead of silently rebalancing."""
    if path is None:
        return None
    with open(path, encoding='utf-8') as stream:
        data = json.load(stream)
    if (not isinstance(data, dict) or type(data.get('schemaVersion')) is not int
            or data['schemaVersion'] != 1):
        raise ValueError('duration data requires schemaVersion 1')
    if not isinstance(data.get('source'), str) or not data['source'].strip():
        raise ValueError('duration data requires source provenance')
    durations = data.get('classSeconds')
    if not isinstance(durations, dict) or not durations:
        raise ValueError('classSeconds must be a nonempty object')
    for name, seconds in durations.items():
        if (not isinstance(name, str) or not name or name != name.strip()
                or '.' in name or name.startswith('/') or '//' in name):
            raise ValueError('duration keys must be includesFile class paths')
        try:
            finite = math.isfinite(seconds) if type(seconds) in (int, float) else False
        except OverflowError:
            finite = False
        if not finite or seconds < 0:
            raise ValueError('class durations must be finite nonnegative seconds')
    return durations


def partition(classes, total, prof, durations=None):
    groups = {}
    plain = []
    for c in sorted(set(classes)):
        key = prof.get(c)
        if key is None:
            plain.append(c)
        else:
            groups.setdefault(key, []).append(c)

    load = [0] * total
    shard_of = {}
    if durations is None:
        # Preserve the existing three-argument assignment exactly.
        units = [(AUGMENTATION_WEIGHT + len(members), members)
                 for key, members in sorted(groups.items(),
                                            key=lambda kv: (-len(kv[1]), kv[0]))]
        units += [(1, [c]) for c in plain]
    else:
        # Reports omit augmentation and new classes. Keep a conservative startup
        # allowance and give unseen classes a deterministic observed median cost.
        known = sorted(durations[c] for c in set(classes) if c in durations)
        fallback = max(1.0, known[len(known) // 2]) if known else 1.0
        def cost(members):
            return sum(max(0.001, durations.get(c, fallback)) for c in members)
        units = [(MEASURED_PROFILE_ALLOWANCE_SECONDS + cost(members), members)
                 for members in groups.values()]
        units += [(cost([c]), [c]) for c in plain]
        units.sort(key=lambda unit: (-unit[0], unit[1]))
    for weight, members in units:
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += weight
        for c in members:
            shard_of[c] = target
    return shard_of


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit('usage: partition.sh <all-classes.txt> <shard-total> <shard-index> [durations.json]')
    try:
        with open(sys.argv[1], encoding='utf-8') as stream:
            classes = [line.strip() for line in stream if line.strip()]
        total, index = int(sys.argv[2]), int(sys.argv[3])
        durations = read_durations(sys.argv[4] if len(sys.argv) == 5 else None)
    except (OSError, ValueError) as e:
        sys.exit(f'partition.sh: {e}')
    if not (1 <= index <= total):
        sys.exit(f'partition.sh: shard index {index} outside 1..{total}')
    shard_of = partition(classes, total, profile_groups('src/test/java'), durations)
    out = sorted(c for c, shard in shard_of.items() if shard == index - 1)
    if not out:
        sys.exit(f'partition.sh: shard {index}/{total} would be empty')
    print('\n'.join(out))


if __name__ == '__main__':
    main()
