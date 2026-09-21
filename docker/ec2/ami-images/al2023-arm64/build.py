#!/usr/bin/env python3
"""Build the opt-in native ARM64 worker and generate an external EC2 catalog."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def run(*args, cwd=None, timeout=1200):
    return subprocess.run(
        args, cwd=cwd, check=True, timeout=timeout, text=True, capture_output=False
    )


def download(url, path, checksum):
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == checksum:
        return
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != checksum:
        raise ValueError("Checksum mismatch: " + url)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="floci/ami-al2023:eks-1.34-arm64-local")
    parser.add_argument(
        "--output", type=Path, default=ROOT / "target/ami-images/al2023-arm64"
    )
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    inputs = json.loads((HERE / "inputs.json").read_text())
    architecture = subprocess.check_output(
        ["docker", "info", "--format", "{{.Architecture}}"], text=True, timeout=15
    ).strip()
    if architecture not in ["aarch64", "arm64"]:
        raise RuntimeError("This recipe is validated only on native ARM64 Docker hosts")
    for name in [
        "Dockerfile",
        "inputs.json",
        "entrypoint-native.sh",
        "kubelet.service",
        "runtime.slice",
        "native-nodeadm.service",
        "pause-image.conf",
    ]:
        shutil.copyfile(HERE / name, out / name)
    for name, checksum in inputs["sha256"].items():
        download(inputs["binaryBaseUrl"] + name, out / name, checksum)
    source = out / "source"
    if not source.exists():
        run("git", "init", str(source), timeout=15)
    run(
        "git",
        "fetch",
        "--depth=1",
        "https://github.com/awslabs/amazon-eks-ami.git",
        inputs["nodeadmCommit"],
        cwd=source,
        timeout=120,
    )
    run("git", "checkout", "--detach", "FETCH_HEAD", cwd=source, timeout=30)
    if subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=source, text=True
    ).strip():
        raise RuntimeError(
            "Nodeadm source has local changes; refusing an unverified build"
        )
    run("docker", "pull", "--platform=linux/arm64", inputs["pauseImage"], timeout=120)
    run("docker", "tag", inputs["pauseImage"], "floci/eks-pause:3.10-arm64", timeout=15)
    run(
        "docker",
        "save",
        "-o",
        str(out / "pause.tar"),
        "floci/eks-pause:3.10-arm64",
        timeout=60,
    )
    run(
        "docker",
        "build",
        "--platform=linux/arm64",
        "--build-arg",
        "GO_IMAGE=" + inputs["goImage"],
        "--build-arg",
        "BASE_IMAGE=" + inputs["baseImage"],
        "--build-arg",
        "AL2023_RELEASE=" + inputs["al2023Release"],
        "--label",
        "io.floci.ami.nodeadm-revision=" + inputs["nodeadmCommit"],
        "-t",
        args.tag,
        str(out),
    )
    image = json.loads(
        subprocess.check_output(
            ["docker", "image", "inspect", args.tag], text=True, timeout=15
        )
    )[0]
    (out / "provenance.json").write_text(
        json.dumps(
            {"inputs": inputs, "image": args.tag, "imageId": image["Id"]}, indent=2
        )
        + "\n"
    )
    catalog = yaml.safe_load(
        (ROOT / "src/main/resources/ec2/image-catalog.yaml").read_text()
    )
    catalog["images"].append(
        {
            "imageId": "ami-floci-al2023-eks-arm64",
            "dockerImage": args.tag,
            "name": "floci-al2023-eks-1.34-arm64-local",
            "description": "Opt-in local AL2023 Kubernetes worker fixture",
            "ownerId": "137112412989",
            "imageOwnerAlias": "amazon",
            "architecture": "arm64",
            "guestRuntime": "systemd",
            "creationDate": "2025-11-05T00:00:00.000Z",
        }
    )
    (out / "image-catalog.yaml").write_text(yaml.safe_dump(catalog, sort_keys=False))
    print("Image and catalog ready:", out)


if __name__ == "__main__":
    main()
