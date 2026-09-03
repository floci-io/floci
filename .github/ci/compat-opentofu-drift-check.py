#!/usr/bin/env python3
"""Fail if compat-terraform and compat-opentofu resource lists diverge unexpectedly.

Usage: compat-opentofu-drift-check.py

Parses the top-level `resource "TYPE" "NAME" {` blocks out of both
compatibility-tests/compat-terraform/main.tf and
compatibility-tests/compat-opentofu/main.tf and fails if either config
declares a resource address the other doesn't, unless that address is listed
in the matching direction's allowlist:
  .github/ci/compat-opentofu-allowlist-tf-only.txt   (terraform-only)
  .github/ci/compat-opentofu-allowlist-otf-only.txt  (opentofu-only)
(one "type.name" per line, `#`-comments and blank lines ignored). Allowlists
are directional so a resource that flips sides -- dropped from one config and
added to the other under the same address -- is still reported as unreviewed
drift rather than silently matching a stale entry from the old direction.

Also fails if an allowlist contains an address that is no longer one-sided
in its direction, so accepted gaps get removed once they're closed instead
of accumulating.
"""
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TF_MAIN = REPO_ROOT / 'compatibility-tests/compat-terraform/main.tf'
OTF_MAIN = REPO_ROOT / 'compatibility-tests/compat-opentofu/main.tf'
ALLOWLIST_TF_ONLY = REPO_ROOT / '.github/ci/compat-opentofu-allowlist-tf-only.txt'
ALLOWLIST_OTF_ONLY = REPO_ROOT / '.github/ci/compat-opentofu-allowlist-otf-only.txt'

RESOURCE_RE = re.compile(r'^resource\s+"([A-Za-z0-9_]+)"\s+"([A-Za-z0-9_]+)"', re.MULTILINE)


def resource_addresses(path):
    return {f'{t}.{n}' for t, n in RESOURCE_RE.findall(path.read_text())}


def load_allowlist(path):
    if not path.exists():
        return set()
    addrs = set()
    for line in path.read_text().splitlines():
        addr = line.split('#', 1)[0].strip()
        if addr:
            addrs.add(addr)
    return addrs


def check_direction(label, only_in, allowlist_path):
    """Report unexpected and stale entries for one direction. Returns True if clean."""
    allowed = load_allowlist(allowlist_path)
    unexpected = sorted(only_in - allowed)
    stale = sorted(allowed - only_in)
    ok = True
    if unexpected:
        ok = False
        print(f'::error::resources in {label} not allowlisted in '
              f'{allowlist_path.relative_to(REPO_ROOT)}:')
        for a in unexpected:
            print(f'  {a}')
    if stale:
        ok = False
        print(f'::error::stale entries in {allowlist_path.relative_to(REPO_ROOT)} '
              f'(no longer one-sided in this direction, remove them):')
        for a in stale:
            print(f'  {a}')
    return ok, allowed


def main():
    tf = resource_addresses(TF_MAIN)
    otf = resource_addresses(OTF_MAIN)

    tf_ok, tf_allowed = check_direction('compat-terraform/main.tf but not compat-opentofu/main.tf',
                                         tf - otf, ALLOWLIST_TF_ONLY)
    otf_ok, otf_allowed = check_direction('compat-opentofu/main.tf but not compat-terraform/main.tf',
                                           otf - tf, ALLOWLIST_OTF_ONLY)

    if not (tf_ok and otf_ok):
        print('\nPort the resource to the other config, or add it to the matching-direction '
              'allowlist if the gap is intentional.')
        sys.exit(1)
    print(f'compat-terraform and compat-opentofu resource lists match '
          f'({len(tf & otf)} shared, {len(tf_allowed)} tf-only allowlisted, '
          f'{len(otf_allowed)} otf-only allowlisted).')


if __name__ == '__main__':
    main()
