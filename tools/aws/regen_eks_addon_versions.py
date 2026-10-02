#!/usr/bin/env python3
"""Regenerate src/main/resources/eks/addon-versions.json from EKS DescribeAddonVersions.

AWS publishes the add-on catalog only through the EKS API, so the source is the AWS CLI:
`aws eks describe-addon-versions --addon-name <name> --kubernetes-version <version>` for every
add-on in ADDONS and every Kubernetes version Floci can run (the k3s image map in
EksClusterManager.java). A filtered response lists only that version's compatibility, so the
responses are merged into one entry per add-on version with every compatible cluster version.

The call is a read that works from any AWS account; it always asks us-east-1, so the caller's
default region cannot change the result. Nothing is hand-typed. Without AWS
credentials (CI), `--check` only verifies the vendored file parses, keeps its shape, and covers
every add-on and Kubernetes version; a regeneration needs credentials.

Run from anywhere in the repo:
    python3 tools/aws/regen_eks_addon_versions.py            # rewrite the vendored file in place
    python3 tools/aws/regen_eks_addon_versions.py --check    # exit 1 when the vendored file is malformed
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REGION = "us-east-1"
REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/eks/addon-versions.json"
CLUSTER_MANAGER = REPO_ROOT / "src/main/java/io/github/hectorvent/floci/services/eks/EksClusterManager.java"

ADDONS = [
    "vpc-cni",
    "coredns",
    "kube-proxy",
    "eks-pod-identity-agent",
    "aws-ebs-csi-driver",
    "amazon-cloudwatch-observability",
]
ADDON_FIELDS = ("addonName", "type", "owner", "publisher")
VERSION_FIELDS = ("addonVersion", "architecture", "compatibilities", "requiresConfiguration",
                  "requiresIamPermissions")
COMPATIBILITY_FIELDS = ("clusterVersion", "platformVersions", "defaultVersion")


def kubernetes_versions(java_source: str) -> list[str]:
    """The cluster versions Floci maps to a k3s image, newest first."""
    versions = set(re.findall(r'"(\d+\.\d+)",\s*"rancher/k3s:', java_source))
    if not versions:
        raise ValueError("no k3s image map found in EksClusterManager.java")
    return sorted(versions, key=version_key, reverse=True)


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version))


def merge(addon_name: str, responses: dict[str, dict]) -> dict:
    """Merges one DescribeAddonVersions response per Kubernetes version into one add-on entry."""
    entry: dict | None = None
    versions: dict[str, dict] = {}
    for kubernetes_version, response in responses.items():
        for addon in response.get("addons", []):
            if addon["addonName"] != addon_name:
                continue
            if entry is None:
                entry = {field: addon[field] for field in ADDON_FIELDS if field in addon}
            for version in addon.get("addonVersions", []):
                merged = versions.setdefault(version["addonVersion"], {
                    "addonVersion": version["addonVersion"],
                    "architecture": version.get("architecture", []),
                    "compatibilities": [],
                    "requiresConfiguration": version.get("requiresConfiguration", False),
                    "requiresIamPermissions": version.get("requiresIamPermissions", False),
                })
                for compatibility in version.get("compatibilities", []):
                    if compatibility.get("clusterVersion") != kubernetes_version:
                        continue
                    if any(existing["clusterVersion"] == kubernetes_version
                           for existing in merged["compatibilities"]):
                        continue
                    merged["compatibilities"].append({
                        "clusterVersion": kubernetes_version,
                        "platformVersions": compatibility.get("platformVersions", ["*"]),
                        "defaultVersion": bool(compatibility.get("defaultVersion", False)),
                    })
    if entry is None:
        raise ValueError(f"AWS returned no versions of {addon_name}")
    for version in versions.values():
        version["compatibilities"].sort(key=lambda c: version_key(c["clusterVersion"]), reverse=True)
    entry["addonVersions"] = sorted((v for v in versions.values() if v["compatibilities"]),
                                    key=lambda v: version_key(v["addonVersion"]), reverse=True)
    return entry


def validate(catalog: dict, addons: list[str], cluster_versions: list[str]) -> list[str]:
    """Problems with the vendored catalog; empty when it is well-formed and complete."""
    problems = []
    entries = catalog.get("addons")
    if not isinstance(entries, list):
        return ["top-level 'addons' list is missing"]
    by_name = {entry.get("addonName"): entry for entry in entries}
    for name in addons:
        entry = by_name.get(name)
        if entry is None:
            problems.append(f"{name}: missing")
            continue
        if set(entry) != set(ADDON_FIELDS) | {"addonVersions"}:
            problems.append(f"{name}: unexpected fields {sorted(entry)}")
        # Terraform's most_recent takes the first release, so the order is part of the contract.
        keys = [version_key(version.get("addonVersion", "")) for version in entry.get("addonVersions", [])]
        if keys != sorted(keys, reverse=True) or len(set(keys)) != len(keys):
            problems.append(f"{name}: releases are not unique and newest first")
        for version in entry.get("addonVersions", []):
            if set(version) != set(VERSION_FIELDS):
                problems.append(f"{name} {version.get('addonVersion')}: unexpected fields {sorted(version)}")
            for compatibility in version.get("compatibilities", []):
                if set(compatibility) != set(COMPATIBILITY_FIELDS):
                    problems.append(f"{name} {version.get('addonVersion')}: unexpected compatibility fields")
        for cluster_version in cluster_versions:
            defaults = [version["addonVersion"] for version in entry.get("addonVersions", [])
                        if any(c.get("clusterVersion") == cluster_version and c.get("defaultVersion")
                               for c in version.get("compatibilities", []))]
            if len(defaults) != 1:
                problems.append(f"{name}: {len(defaults)} default versions for Kubernetes {cluster_version}")
    extra = sorted(set(by_name) - set(addons))
    if extra:
        problems.append(f"add-ons not in ADDONS: {extra}")
    return problems


def describe(addon_name: str, kubernetes_version: str) -> dict:
    result = subprocess.run(
        ["aws", "eks", "describe-addon-versions", "--region", REGION, "--addon-name", addon_name,
         "--kubernetes-version", kubernetes_version, "--output", "json"],
        check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def render(catalog: dict) -> str:
    """One add-on version per line, so a new AWS release is a one-line diff."""
    compact = {"separators": (",", ":")}
    addons = []
    for addon in catalog["addons"]:
        header = json.dumps({field: addon[field] for field in ADDON_FIELDS if field in addon}, **compact)
        versions = ",\n".join(json.dumps({field: version[field] for field in VERSION_FIELDS}, **compact)
                              for version in addon["addonVersions"])
        addons.append(header[:-1] + ',"addonVersions":[\n' + versions + "\n]}")
    return '{"addons":[\n' + ",\n".join(addons) + "\n]}\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="verify the vendored file instead of rewriting it")
    args = parser.parse_args()
    try:
        cluster_versions = kubernetes_versions(CLUSTER_MANAGER.read_text())
    except ValueError as error:
        print(f"regen_eks_addon_versions: {error}", file=sys.stderr)
        return 1
    if args.check:
        problems = validate(json.loads(OUTPUT.read_text()), ADDONS, cluster_versions)
        for problem in problems:
            print(f"addon-versions.json: {problem}", file=sys.stderr)
        return 1 if problems else 0
    catalog = {"addons": [merge(name, {version: describe(name, version) for version in cluster_versions})
                          for name in ADDONS]}
    problems = validate(catalog, ADDONS, cluster_versions)
    if problems:
        for problem in problems:
            print(f"generated catalog: {problem}", file=sys.stderr)
        return 1
    OUTPUT.write_text(render(catalog))
    return 0


if __name__ == "__main__":
    sys.exit(main())
