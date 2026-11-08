#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/rds-ca-certificates.json from the RDS certificate bundles AWS publishes.

`DescribeCertificates` reports, for each RDS certificate authority in a Region, a SHA-1
`Thumbprint` and its `ValidFrom` and `ValidTill`. Those are the SHA-1 fingerprint and validity of
the Region's root CA certificate in the bundle AWS publishes for that Region: the us-west-2 example
in the AWS CLI reference for `describe-certificates` matches the root certificates of
`us-west-2-bundle.pem` exactly, for all three CAs. The CA's identifier follows from the root's
common name, `Amazon RDS <region> Root CA <RSA2048|RSA4096|ECC384> G1`, as
`rds-ca-<rsa2048|rsa4096|ecc384>-g1`.

The bundles are read from `local/aws/rds-truststore/<region>-bundle.pem`, one per Region of the
`aws` partition in `partitions.json`. AWS serves them at
    https://truststore.pki.rds.amazonaws.com/<region>/<region>-bundle.pem
and `--download` saves them there; a Region AWS serves no bundle for is left out. Other partitions
are left out: the one GovCloud thumbprint AWS shows (re:Post, us-gov-east-1) is not its root's.

The user guide ("Certificate bundles by AWS Region", UsingWithRDS.SSL.html) says rds-ca-rsa4096-g1
and rds-ca-ecc384-g1 are not available in six Regions, though their bundles carry those roots. For
those Regions only rds-ca-rsa2048-g1 is kept.

Run from anywhere in the repo:
    python3 tools/aws/regen_rds_ca_certificates.py --download   # fetch the bundles into local/aws
    python3 tools/aws/regen_rds_ca_certificates.py              # rewrite the vendored file in place
    python3 tools/aws/regen_rds_ca_certificates.py --check      # exit 1 when the vendored file is stale

Without the bundles, `--check` verifies the vendored file's shape only.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from request_shapes import REPO_ROOT

OUTPUT = REPO_ROOT / "src/main/resources/aws/rds-ca-certificates.json"
PARTITIONS = REPO_ROOT / "src/main/resources/aws/partitions.json"
LOCAL_BUNDLES = REPO_ROOT / "local/aws/rds-truststore"
BUNDLE_URL = "https://truststore.pki.rds.amazonaws.com/{region}/{region}-bundle.pem"
ROOT_CN = re.compile(r"Amazon RDS (?P<region>[a-z0-9-]+) Root CA (?P<kind>RSA2048|RSA4096|ECC384) G1")
PEM = re.compile(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", re.S)
COMMON_NAME_OID = bytes([0x55, 0x04, 0x03])
IDENTIFIER = re.compile(r"rds-ca-(rsa2048|rsa4096|ecc384)-g1")
INSTANT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
DEFAULT_CA = "rds-ca-rsa2048-g1"
# Mumbai, Melbourne, Calgary, Zurich, Spain and Tel Aviv, as the user guide lists them.
RSA2048_ONLY = frozenset({"ap-south-1", "ap-southeast-4", "ca-west-1", "eu-central-2", "eu-south-2",
                          "il-central-1"})
# What the truststore answers for a Region it serves no bundle for.
NO_BUNDLE = frozenset({403, 404})


def _tlv(data: bytes, offset: int) -> tuple[int, int, int]:
    """The tag, the content's start and the content's end of the DER element at `offset`."""
    tag = data[offset]
    length = data[offset + 1]
    start = offset + 2
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[start:start + count], "big")
        start += count
    return tag, start, start + length


def _children(data: bytes, start: int, end: int) -> list[tuple[int, int, int]]:
    out = []
    while start < end:
        tag, content, stop = _tlv(data, start)
        out.append((tag, content, stop))
        start = stop
    return out


def _time(data: bytes, tag: int, start: int, end: int) -> str:
    text = data[start:end].decode("ascii")
    parsed = datetime.strptime(text, "%y%m%d%H%M%SZ" if tag == 0x17 else "%Y%m%d%H%M%SZ")
    return parsed.replace(tzinfo=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _common_name(data: bytes, start: int, end: int) -> str | None:
    for _, rdn_start, rdn_end in _children(data, start, end):
        for _, atv_start, atv_end in _children(data, rdn_start, rdn_end):
            (_, oid_start, oid_end), (_, value_start, value_end) = _children(data, atv_start, atv_end)[:2]
            if data[oid_start:oid_end] == COMMON_NAME_OID:
                return data[value_start:value_end].decode("utf-8")
    return None


def read_certificate(der: bytes) -> dict:
    """The subject common name, validity and SHA-1 fingerprint of one DER certificate."""
    _, cert_start, cert_end = _tlv(der, 0)
    _, tbs_start, tbs_end = _children(der, cert_start, cert_end)[0]
    fields = _children(der, tbs_start, tbs_end)
    if fields[0][0] == 0xA0:  # the explicit version
        fields = fields[1:]
    # serialNumber, signature, issuer, validity, subject
    _, validity_start, validity_end = fields[3]
    (nb_tag, nb_start, nb_end), (na_tag, na_start, na_end) = _children(der, validity_start, validity_end)
    _, subject_start, subject_end = fields[4]
    return {
        "commonName": _common_name(der, subject_start, subject_end),
        "validFrom": _time(der, nb_tag, nb_start, nb_end),
        "validTill": _time(der, na_tag, na_start, na_end),
        "thumbprint": hashlib.sha1(der).hexdigest(),
    }


def certificates_in(pem_text: str, region: str) -> list[dict]:
    """The bundle's root CAs for `region`, by identifier."""
    found = []
    for block in PEM.findall(pem_text):
        cert = read_certificate(base64.b64decode("".join(block.split())))
        match = ROOT_CN.fullmatch(cert["commonName"] or "")
        if match and match.group("region") == region:
            found.append({
                "certificateIdentifier": f"rds-ca-{match.group('kind').lower()}-g1",
                "thumbprint": cert["thumbprint"],
                "validFrom": cert["validFrom"],
                "validTill": cert["validTill"],
            })
    return sorted(found, key=lambda c: c["certificateIdentifier"])


def offered_in(region: str, certificates: list[dict]) -> list[dict]:
    """The CAs RDS offers in `region`, which is fewer than its bundle carries in RSA2048_ONLY."""
    if region in RSA2048_ONLY:
        return [c for c in certificates if c["certificateIdentifier"] == DEFAULT_CA]
    return certificates


def commercial_regions(partitions: Path) -> list[str]:
    doc = json.loads(partitions.read_text(encoding="utf-8"))
    aws = next(p for p in doc["partitions"] if p["id"] == "aws")
    return sorted(r["id"] for r in aws["regions"])


def build(bundles: Path, partitions: Path) -> dict:
    regions = {}
    for region in commercial_regions(partitions):
        path = bundles / f"{region}-bundle.pem"
        if path.exists():
            certificates = offered_in(region, certificates_in(path.read_text(encoding="utf-8"), region))
            if certificates:
                regions[region] = certificates
    return {
        "_source": {
            "generator": "tools/aws/regen_rds_ca_certificates.py",
            "bundles": BUNDLE_URL,
        },
        "regions": regions,
    }


def download(bundles: Path, partitions: Path) -> list[str]:
    """Saves each Region's bundle and returns the Regions that failed for a reason other than having none."""
    bundles.mkdir(parents=True, exist_ok=True)
    failed = []
    for region in commercial_regions(partitions):
        try:
            with urllib.request.urlopen(BUNDLE_URL.format(region=region), timeout=30) as response:
                (bundles / f"{region}-bundle.pem").write_bytes(response.read())
            print(f"saved {region}")
        except urllib.error.HTTPError as e:
            if e.code in NO_BUNDLE:
                print(f"skipped {region}: HTTP {e.code}, no bundle published")
            else:
                print(f"failed {region}: HTTP {e.code}", file=sys.stderr)
                failed.append(region)
        except OSError as e:  # URLError, a timeout or a refused connection
            print(f"failed {region}: {e}", file=sys.stderr)
            failed.append(region)
    return failed


def self_check(output: Path) -> list[str]:
    """What a stale-proof check can say without the bundles: the file parses and keeps its shape."""
    try:
        doc = json.loads(output.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        return [f"cannot read {output}: {e}"]
    problems = []
    regions = doc.get("regions")
    if not isinstance(regions, dict) or "us-east-1" not in regions:
        return ["regions must be an object that includes us-east-1"]
    for region, certificates in regions.items():
        identifiers = [c.get("certificateIdentifier") for c in certificates]
        if DEFAULT_CA not in identifiers:
            problems.append(f"{region}: no {DEFAULT_CA}")
        if region in RSA2048_ONLY and identifiers != [DEFAULT_CA]:
            problems.append(f"{region}: offers only {DEFAULT_CA}")
        for c in certificates:
            if not IDENTIFIER.fullmatch(str(c.get("certificateIdentifier"))):
                problems.append(f"{region}: bad identifier {c.get('certificateIdentifier')!r}")
            if not re.fullmatch(r"[0-9a-f]{40}", str(c.get("thumbprint"))):
                problems.append(f"{region}: bad thumbprint for {c.get('certificateIdentifier')}")
            if not (INSTANT.fullmatch(str(c.get("validFrom"))) and INSTANT.fullmatch(str(c.get("validTill")))):
                problems.append(f"{region}: bad validity for {c.get('certificateIdentifier')}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--download", action="store_true", help="fetch the Region bundles into --bundles and exit")
    parser.add_argument("--bundles", type=Path, default=LOCAL_BUNDLES, help="directory of <region>-bundle.pem files")
    parser.add_argument("--partitions", type=Path, default=PARTITIONS, help="vendored partitions.json")
    parser.add_argument("--output", type=Path, default=OUTPUT, help="vendored file to write or check")
    args = parser.parse_args(argv)

    if args.download:
        failed = download(args.bundles, args.partitions)
        if failed:
            print(f"error: no bundle saved for {', '.join(failed)}; run --download again", file=sys.stderr)
            return 1
        return 0
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output
    if not (args.bundles / "us-east-1-bundle.pem").exists():
        if args.check:
            problems = self_check(args.output)
            for problem in problems:
                print(f"{rel}: {problem}", file=sys.stderr)
            if not problems:
                print(f"{rel}: bundles not downloaded, shape verified only")
            return 1 if problems else 0
        raise SystemExit("error: regenerating needs the bundles under local/aws/rds-truststore; run with --download")

    fresh = json.dumps(build(args.bundles, args.partitions), indent=2) + "\n"
    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if current != fresh:
            print(f"{rel} is out of date", file=sys.stderr)
            return 1
        print(f"{rel} is up to date")
        return 0
    args.output.write_text(fresh, encoding="utf-8")
    print(f"wrote {rel} for {len(json.loads(fresh)['regions'])} Regions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
