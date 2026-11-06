"""Tests for regen_rds_ca_certificates.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json
from pathlib import Path

import regen_rds_ca_certificates as r


# The us-west-2 ECC384 root from AWS's us-west-2-bundle.pem. The AWS CLI reference's
# describe-certificates example gives this CA thumbprint 2ee3dcc06e50192559b13929e73484354f23387d,
# ValidFrom 2021-05-24T22:06:59 and ValidTill 2121-05-24T23:06:59.
US_WEST_2_ECC384 = """
-----BEGIN CERTIFICATE-----
MIICrjCCAjSgAwIBAgIRAOxu0I1QuMAhIeszB3fJIlkwCgYIKoZIzj0EAwMwgZYx
CzAJBgNVBAYTAlVTMSIwIAYDVQQKDBlBbWF6b24gV2ViIFNlcnZpY2VzLCBJbmMu
MRMwEQYDVQQLDApBbWF6b24gUkRTMQswCQYDVQQIDAJXQTEvMC0GA1UEAwwmQW1h
em9uIFJEUyB1cy13ZXN0LTIgUm9vdCBDQSBFQ0MzODQgRzExEDAOBgNVBAcMB1Nl
YXR0bGUwIBcNMjEwNTI0MjIwNjU5WhgPMjEyMTA1MjQyMzA2NTlaMIGWMQswCQYD
VQQGEwJVUzEiMCAGA1UECgwZQW1hem9uIFdlYiBTZXJ2aWNlcywgSW5jLjETMBEG
A1UECwwKQW1hem9uIFJEUzELMAkGA1UECAwCV0ExLzAtBgNVBAMMJkFtYXpvbiBS
RFMgdXMtd2VzdC0yIFJvb3QgQ0EgRUNDMzg0IEcxMRAwDgYDVQQHDAdTZWF0dGxl
MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEz4bylRcGqqDWdP7gQIIoTHdBK6FNtKH1
4SkEIXRXkYDmRvL9Bci1MuGrwuvrka5TDj4b7e+csY0llEzHpKfq6nJPFljoYYP9
uqHFkv77nOpJJ633KOr8IxmeHW5RXgrZo0IwQDAPBgNVHRMBAf8EBTADAQH/MB0G
A1UdDgQWBBQQikVz8wmjd9eDFRXzBIU8OseiGzAOBgNVHQ8BAf8EBAMCAYYwCgYI
KoZIzj0EAwMDaAAwZQIwf06Mcrpw1O0EBLBBrp84m37NYtOkE/0Z0O+C7D41wnXi
EQdn6PXUVgdD23Gj82SrAjEAklhKs+liO1PtN15yeZR1Io98nFve+lLptaLakZcH
+hfFuUtCqMbaI8CdvJlKnPqT
-----END CERTIFICATE-----
"""


def partitions(tmp_path: Path, regions: list[str]) -> Path:
    path = tmp_path / "partitions.json"
    path.write_text(json.dumps({"partitions": [
        {"id": "aws", "regions": [{"id": region} for region in regions]},
        {"id": "aws-us-gov", "regions": [{"id": "us-gov-west-1"}]},
    ]}), encoding="utf-8")
    return path


def test_a_root_reads_as_the_cli_reference_reports_it():
    assert r.certificates_in(US_WEST_2_ECC384, "us-west-2") == [{
        "certificateIdentifier": "rds-ca-ecc384-g1",
        "thumbprint": "2ee3dcc06e50192559b13929e73484354f23387d",
        "validFrom": "2021-05-24T22:06:59Z",
        "validTill": "2121-05-24T23:06:59Z",
    }]


def test_a_root_named_for_another_region_is_left_out():
    assert r.certificates_in(US_WEST_2_ECC384, "us-east-1") == []


def test_build_takes_the_aws_partition_regions_that_have_a_bundle(tmp_path):
    bundles = tmp_path / "bundles"
    bundles.mkdir()
    (bundles / "us-west-2-bundle.pem").write_text(US_WEST_2_ECC384, encoding="utf-8")
    (bundles / "us-gov-west-1-bundle.pem").write_text(US_WEST_2_ECC384, encoding="utf-8")
    doc = r.build(bundles, partitions(tmp_path, ["us-east-1", "us-west-2"]))
    assert list(doc["regions"]) == ["us-west-2"]
    assert doc["_source"]["generator"] == "tools/aws/regen_rds_ca_certificates.py"


def test_check_reports_a_stale_file(tmp_path, capsys):
    bundles = tmp_path / "bundles"
    bundles.mkdir()
    (bundles / "us-east-1-bundle.pem").write_text(US_WEST_2_ECC384, encoding="utf-8")
    (bundles / "us-west-2-bundle.pem").write_text(US_WEST_2_ECC384, encoding="utf-8")
    output = tmp_path / "out.json"
    args = ["--bundles", str(bundles), "--partitions", str(partitions(tmp_path, ["us-east-1", "us-west-2"])),
            "--output", str(output)]
    assert r.main(args) == 0
    assert r.main(args + ["--check"]) == 0
    output.write_text("{}", encoding="utf-8")
    assert r.main(args + ["--check"]) == 1
    assert "out of date" in capsys.readouterr().err


def test_without_bundles_the_check_verifies_the_shape(tmp_path):
    output = tmp_path / "out.json"
    entry = {"certificateIdentifier": "rds-ca-rsa2048-g1", "thumbprint": "a" * 40,
             "validFrom": "2021-05-25T22:34:57Z", "validTill": "2061-05-25T23:34:57Z"}
    output.write_text(json.dumps({"regions": {"us-east-1": [entry]}}), encoding="utf-8")
    args = ["--bundles", str(tmp_path / "none"), "--output", str(output), "--check"]
    assert r.main(args) == 0
    assert r.self_check(output) == []

    for broken in ({"regions": {"eu-west-1": [entry]}},
                   {"regions": {"us-east-1": [dict(entry, certificateIdentifier="rds-ca-2019")]}},
                   {"regions": {"us-east-1": [entry, dict(entry, certificateIdentifier="rds-ca-ecc384-g1",
                                                          thumbprint="XYZ")]}},
                   {"regions": {"us-east-1": [dict(entry, validTill="2061-05-25")]}}):
        output.write_text(json.dumps(broken), encoding="utf-8")
        assert r.self_check(output), broken
        assert r.main(args) == 1


def test_the_vendored_file_keeps_its_shape():
    assert r.self_check(r.OUTPUT) == []


def test_the_six_regions_the_user_guide_names_offer_only_rsa2048():
    def ca(identifier):
        return {"certificateIdentifier": identifier, "thumbprint": "a" * 40,
                "validFrom": "2021-05-25T22:34:57Z", "validTill": "2061-05-25T23:34:57Z"}
    bundle = [ca("rds-ca-ecc384-g1"), ca("rds-ca-rsa2048-g1"), ca("rds-ca-rsa4096-g1")]
    assert r.RSA2048_ONLY == {"ap-south-1", "ap-southeast-4", "ca-west-1", "eu-central-2", "eu-south-2",
                              "il-central-1"}
    for region in r.RSA2048_ONLY:
        assert r.offered_in(region, bundle) == [ca("rds-ca-rsa2048-g1")]
    assert r.offered_in("us-west-2", bundle) == bundle


def test_the_shape_check_refuses_another_ca_in_the_six_regions(tmp_path):
    output = tmp_path / "out.json"
    entry = {"certificateIdentifier": "rds-ca-rsa2048-g1", "thumbprint": "a" * 40,
             "validFrom": "2021-05-25T22:34:57Z", "validTill": "2061-05-25T23:34:57Z"}
    output.write_text(json.dumps({"regions": {"us-east-1": [entry], "ap-south-1": [
        entry, dict(entry, certificateIdentifier="rds-ca-ecc384-g1")]}}), encoding="utf-8")
    assert r.self_check(output) == ["ap-south-1: offers only rds-ca-rsa2048-g1"]


def test_download_skips_a_region_with_no_bundle_and_fails_on_anything_else(tmp_path, monkeypatch, capsys):
    import io
    import urllib.error

    def urlopen(url, timeout):
        if "/us-east-1/" in url:
            return io.BytesIO(US_WEST_2_ECC384.encode())
        if "/us-west-1/" in url:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        if "/us-west-2/" in url:
            raise urllib.error.HTTPError(url, 503, "Service Unavailable", {}, None)
        raise urllib.error.URLError("name resolution failed")

    monkeypatch.setattr(r.urllib.request, "urlopen", urlopen)
    bundles = tmp_path / "bundles"
    regions = partitions(tmp_path, ["us-east-1", "us-west-1", "us-west-2", "eu-west-1"])
    assert r.download(bundles, regions) == ["eu-west-1", "us-west-2"]
    assert sorted(p.name for p in bundles.iterdir()) == ["us-east-1-bundle.pem"]
    assert r.main(["--download", "--bundles", str(bundles), "--partitions", str(regions)]) == 1
    assert "no bundle saved for eu-west-1, us-west-2" in capsys.readouterr().err
