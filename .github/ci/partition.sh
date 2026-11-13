#!/usr/bin/env python3
"""Deterministic group-by-profile test partitioner for CI sharding.

Usage: partition.sh <all-classes.txt> <shard-total> <shard-index>

<all-classes.txt> holds one test class per line in surefire includesFile path
format (io/github/.../FooTest, no extension). Classes sharing a @TestProfile are
assigned to the SAME shard atomically: per-shard Quarkus augmentation cost is
(distinct profiles in shard) x ~5.4s, so splitting a profile group pays its
augmentation once per shard it touches. Profile-less classes fill the remainder.
A class counts as one, but for the few in SLOW_CLASSES, whose run time would
otherwise be invisible to a partition that balances class counts.

Deterministic: sorted inputs, greedy least-loaded bin-packing, no RNG/timestamps.
Exit 2 on bad arguments or an unreadable input file.
"""
import os
import re
import sys

AUGMENTATION_WEIGHT = 10  # one augmentation ~ 5.4s ~ 10 average test classes

# Classes that take far longer than the suite's average, by class name, which a package rename
# leaves alone, with the seconds they take on GitHub's runners. Each of these two is worth some
# 220 average classes, and the shard that held them both ran over 200s longer than any other
# (1246s of Surefire's 1500s, against 641s to 869s for the rest). A shard's other classes are
# packed around them; a class that stops being this slow only leaves the shards a little uneven.
# They took 651s and 402s on 2026-11-12, once their cases had grown, and weighed at 280s and 260s
# their shard ran into Surefire's 1500s while the others finished in 16 to 20 minutes.
SLOW_CLASSES = {
    'EmrDockerRunnerDockerIntegrationTest': 650,
    'EmrHiveStepsDockerIntegrationTest': 400,
}
SECONDS_PER_CLASS = 1.2  # the suite's mean: 4212s over 3451 classes on 2026-11-09

def weight(cls):
    """How many average test classes a class is worth."""
    seconds = SLOW_CLASSES.get(cls.rsplit('/', 1)[-1])
    return 1 if seconds is None else round(seconds / SECONDS_PER_CLASS)

def warn_of_stale_slow_classes(classes):
    """A renamed class would quietly lose its weight, so name it where the run's annotations show it."""
    known = {c.rsplit('/', 1)[-1] for c in classes}
    for name in sorted(set(SLOW_CLASSES) - known):
        print(f'::warning title=partition.sh::stale SLOW_CLASSES key: no test class named {name}', file=sys.stderr)

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

def main():
    if len(sys.argv) != 4:
        sys.exit('usage: partition.sh <all-classes.txt> <shard-total> <shard-index>')
    try:
        classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
        total, index = int(sys.argv[2]), int(sys.argv[3])
    except (OSError, ValueError) as e:
        sys.exit(f'partition.sh: {e}')
    if not (1 <= index <= total):
        sys.exit(f'partition.sh: shard index {index} outside 1..{total}')

    warn_of_stale_slow_classes(classes)
    prof = profile_groups('src/test/java')
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
    # Heaviest first; ties broken by name for determinism.
    for key, members in sorted(groups.items(), key=lambda kv: (-sum(map(weight, kv[1])), kv[0])):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += AUGMENTATION_WEIGHT + sum(map(weight, members))
        for c in members:
            shard_of[c] = target
    for c in sorted(plain, key=lambda c: (-weight(c), c)):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += weight(c)
        shard_of[c] = target

    out = sorted(c for c, s in shard_of.items() if s == index - 1)
    if not out:
        sys.exit(f'partition.sh: shard {index}/{total} would be empty')
    print('\n'.join(out))

if __name__ == '__main__':
    main()
