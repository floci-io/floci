#!/usr/bin/env python3
"""Run the local native AL2023 acceptance fixture with bounded stages and teardown."""

import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "tools/eks-native/templates"
SERVER = "floci-native-e2e"
CONTROL = "floci-eksnative-eks-native-e2e"
PREFIX = "floci-eksnative-ec2-"
NETWORKS = [
    "floci-native-e2e",
    "floci-eksnative-vpc-4568-us-east-1-vpc-default-us-east-1",
]


class Run:
    def __init__(self, args):
        self.args = args
        self.name = datetime.datetime.now(datetime.timezone.utc).strftime(
            "%Y%m%dT%H%M%SZ"
        )
        self.out = ROOT / "target/eks-native/evidence" / self.name
        self.work = ROOT / "target/eks-native/runs" / self.name
        self.out.mkdir(parents=True)
        self.started = time.monotonic()
        self.records = []
        self.env = dict(
            os.environ,
            AWS_ACCESS_KEY_ID="000000000000",
            AWS_SECRET_ACCESS_KEY="test",
            AWS_DEFAULT_REGION="us-east-1",
            AWS_MAX_ATTEMPTS="1",
            AWS_PAGER="",
        )
        self.env.pop("AWS_SESSION_TOKEN", None)
        self.env["PYTHONOPTIMIZE"] = ""
        self.env["PATH"] = (
            str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"]
        )
        self.owned = False

    def command(self, name, args, timeout=45, attempts=1, required=True):
        for attempt in range(1, attempts + 1):
            start = time.monotonic()
            with subprocess.Popen(
                args,
                cwd=ROOT,
                env=self.env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
            ) as proc:
                try:
                    output, errors = proc.communicate(timeout=timeout)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    output, errors = proc.communicate()
                    output += f"\nTIMEOUT after {timeout}s\n"
            with (self.out / (name + ".log")).open("a") as log:
                log.write(output + errors)
            self.records.append(
                {
                    "stage": name,
                    "attempt": attempt,
                    "exit": proc.returncode,
                    "seconds": round(time.monotonic() - start, 2),
                }
            )
            if proc.returncode == 0:
                return output
            if attempt < attempts:
                time.sleep(3)
        if required:
            raise RuntimeError(f"{name} failed; see {self.out / (name + '.log')}")
        return None

    def aws(self, name, *args, required=True):
        return self.command(
            name,
            [
                "aws",
                "--endpoint-url",
                "http://localhost:4568",
                "--cli-connect-timeout",
                "5",
                "--cli-read-timeout",
                "20",
                *args,
            ],
            timeout=35,
            required=required,
        )

    def kube(self, name, *args, timeout=60, attempts=3, required=True):
        return self.command(
            name,
            [
                "kubectl",
                "--kubeconfig",
                str(self.work / "kubeconfig"),
                "--request-timeout=15s",
                *args,
            ],
            timeout,
            attempts,
            required,
        )

    def helper(self, name, timeout=60, attempts=1):
        return self.command(
            name, [sys.executable, str(self.work / (name + ".py"))], timeout, attempts
        )

    def preflight(self):
        for binary in ["docker", "kubectl", "aws", "helm", "curl"]:
            if not shutil.which(binary):
                raise RuntimeError(f"Missing prerequisite: {binary}")
        import yaml
        import cryptography  # noqa: F401

        if not self.args.catalog.is_file():
            raise RuntimeError("Build the native worker and catalog first")
        if shutil.disk_usage(ROOT).free < self.args.min_host_gib * 1024**3:
            raise RuntimeError(
                f"Need at least {self.args.min_host_gib} GiB free on host"
            )
        self.command(
            "docker-health", ["docker", "info", "--format", "{{.ServerVersion}}"], 10
        )
        containers = self.command(
            "containers-before", ["docker", "ps", "-a", "--format", "{{.Names}}"], 10
        ).splitlines()
        if any(n in [SERVER, CONTROL] or n.startswith(PREFIX) for n in containers):
            raise RuntimeError(
                "Existing native E2E containers found; refusing to reuse or remove them"
            )
        networks = self.command(
            "networks-before", ["docker", "network", "ls", "--format", "{{.Name}}"], 10
        ).splitlines()
        if any(n in networks for n in NETWORKS):
            raise RuntimeError("Existing E2E network found; refusing to reuse it")
        for port in [4568, 9171, 6620]:
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", port))
        catalog = yaml.safe_load(self.args.catalog.read_text())
        workers = [
            entry
            for entry in catalog["images"]
            if entry["imageId"] == "ami-floci-al2023-eks-arm64"
        ]
        if len(workers) != 1:
            raise RuntimeError("Catalog must contain exactly one native worker fixture")
        worker_image = workers[0]["dockerImage"]
        provenance = {}
        for name, image in [
            ("floci", self.args.image),
            ("worker", worker_image),
        ]:
            info = json.loads(
                self.command(name + "-image", ["docker", "image", "inspect", image], 15)
            )[0]
            provenance[name] = {
                "image": image,
                "id": info["Id"],
                "architecture": info["Architecture"],
                "revision": (info["Config"].get("Labels") or {}).get(
                    "org.opencontainers.image.revision"
                ),
            }
        if provenance["floci"]["revision"] != self.args.revision:
            raise RuntimeError(
                "Floci image revision does not match requested source revision"
            )
        df = self.command(
            "docker-disk",
            [
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "--entrypoint",
                "/bin/df",
                worker_image,
                "-Pk",
                "/",
            ],
            30,
        )
        fields = df.splitlines()[-1].split()
        free, total = int(fields[3]), int(fields[1])
        if free < 8 * 1024**2 or free / total < 0.20:
            raise RuntimeError(
                "Docker needs at least 8 GiB and 20% free before provisioning"
            )
        provenance.update(
            {
                "hostFreeGiB": round(shutil.disk_usage(ROOT).free / 1024**3, 1),
                "dockerFreeGiB": round(free / 1024**2, 1),
                "dockerFreePercent": round(free / total * 100, 1),
                "fixture": "Local ARM64 AL2023 image and AMI catalog entry",
                "instanceProfilePath": "/karpenter/native-e2e/",
            }
        )
        self.save("provenance.json", provenance)

    def save(self, name, value):
        (self.out / name).write_text(json.dumps(value, indent=2) + "\n")

    def prepare(self):
        self.work.mkdir(parents=True)
        self.env["EKS_NATIVE_IMAGE"] = self.args.image
        self.env["EKS_NATIVE_CATALOG"] = str(self.args.catalog.resolve())
        for source in TEMPLATE.iterdir():
            if not source.is_file() or source.name in [
                "kubeconfig",
                "provision-final.yaml",
                "resume.sh",
            ]:
                continue
            text = source.read_text().replace(
                "tools/eks-native/templates", str(self.work.relative_to(ROOT))
            )
            text = text.replace(
                "target/eks-native/evidence", str(self.out.relative_to(ROOT))
            )
            (self.work / source.name).write_text(text)
        # Context and credentials are always restricted to this disposable emulator.
        self.env["KUBECONFIG"] = str(self.work / "kubeconfig")

    def exercise(self):
        self.owned = True
        self.command("start-floci", ["bash", str(self.work / "start-floci.sh")], 90)
        self.helper("setup", 150)
        self.command(
            "control-plane-config",
            [
                "docker",
                "cp",
                str(self.work / "k3s-config.yaml"),
                CONTROL + ":/etc/rancher/k3s/config.yaml",
            ],
            20,
        )
        # This initial restart applies the documented k3s fixture config, not recovery.
        self.command("initial-control-plane-start", ["docker", "restart", CONTROL], 60)
        self.helper("configure-cluster", 60, 3)
        self.kube("api-ready", "get", "--raw=/readyz", attempts=10)
        self.aws(
            "access-entry",
            "eks",
            "create-access-entry",
            "--cluster-name",
            "native-e2e",
            "--principal-arn",
            "arn:aws:iam::000000000000:role/native-e2e-node",
            "--type",
            "EC2_LINUX",
        )
        subnet = json.loads(
            self.aws(
                "subnets",
                "ec2",
                "describe-subnets",
                "--subnet-ids",
                "subnet-default-us-east-1-a",
            )
        )
        self.aws(
            "security-group",
            "ec2",
            "create-security-group",
            "--group-name",
            "native-e2e",
            "--description",
            "Owned native e2e fixture",
            "--vpc-id",
            subnet["Subnets"][0]["VpcId"],
            "--tag-specifications",
            "ResourceType=security-group,Tags=[{Key=floci.test/native-e2e,Value=true}]",
        )
        crds = self.command(
            "fetch-crds",
            [
                "helm",
                "show",
                "crds",
                "oci://public.ecr.aws/karpenter/karpenter",
                "--version",
                "1.8.8",
            ],
            90,
            2,
        )
        (self.work / "karpenter-crds.yaml").write_text(crds)
        self.kube("crds", "apply", "-f", str(self.work / "karpenter-crds.yaml"))
        self.kube(
            "crds-established",
            "wait",
            "--for=condition=Established",
            "crd/ec2nodeclasses.karpenter.k8s.aws",
            "crd/nodeclaims.karpenter.sh",
            "crd/nodepools.karpenter.sh",
            "--timeout=60s",
            timeout=75,
        )
        self.command(
            "karpenter-install",
            [
                "helm",
                "upgrade",
                "--install",
                "karpenter",
                "oci://public.ecr.aws/karpenter/karpenter",
                "--version",
                "1.8.8",
                "--namespace",
                "karpenter",
                "--create-namespace",
                "--skip-crds",
                "-f",
                str(self.work / "karpenter-values.yaml"),
                "--timeout",
                "180s",
            ],
            210,
            2,
        )
        self.kube(
            "karpenter-ready",
            "-n",
            "karpenter",
            "rollout",
            "status",
            "deployment/karpenter",
            "--timeout=180s",
            timeout=195,
        )
        self.kube(
            "networking",
            "apply",
            "-f",
            str(self.work / "flannel-runtime.yaml"),
            "-f",
            str(self.work / "kube-proxy.yaml"),
        )
        self.helper("configure-ccm")
        self.kube(
            "cloud-controller",
            "apply",
            "-f",
            str(self.work / "aws-ccm-rbac.yaml"),
            "-f",
            str(self.work / "aws-ccm.yaml"),
        )
        self.helper("prepare-userdata")
        self.kube("nodes-before", "get", "nodes", "-o", "json")
        self.kube("provision", "apply", "-f", str(self.work / "provision-final.yaml"))
        self.kube(
            "nodeclass-ready",
            "wait",
            "--for=condition=Ready",
            "ec2nodeclass/native-e2e",
            "--timeout=120s",
            timeout=135,
        )
        self.kube(
            "network-target", "apply", "-f", str(self.work / "network-target.yaml")
        )
        self.kube(
            "network-target-ready",
            "-n",
            "native-e2e",
            "rollout",
            "status",
            "deployment/network-target",
            "--timeout=120s",
            timeout=135,
        )
        self.helper("accept", 600)
        self.helper("probe-auth", 90, 3)
        auth = json.loads((self.out / "auth-probe.json").read_text())
        assert auth["temporaryKey"] and auth["hasSessionToken"]
        assert auth["review"]["status"]["authenticated"] is True
        assert auth["review"]["status"]["user"]["username"].startswith("system:node:")
        assert (
            auth["apiReview"]["status"]["userInfo"]["username"]
            == auth["review"]["status"]["user"]["username"]
        )
        instances = json.loads(
            self.aws("instance-profiles", "ec2", "describe-instances")
        )
        running = [
            i
            for r in instances["Reservations"]
            for i in r["Instances"]
            if i["State"]["Name"] == "running"
        ]
        assert len(running) == 1
        assert "/karpenter/native-e2e/" in running[0]["IamInstanceProfile"]["Arn"]
        self.save("instance-profile-evidence.json", running)

    def diagnostics(self):
        # Each capture is independent so one unavailable API does not lose all evidence.
        for name, args in [
            ("nodes-final", ["get", "nodes", "-o", "json"]),
            ("csrs-final", ["get", "csr", "-o", "json"]),
            ("events-final", ["get", "events", "-A", "-o", "json"]),
            ("pods-final", ["get", "pods", "-A", "-o", "json"]),
        ]:
            self.kube(name, *args, timeout=20, attempts=1, required=False)
        self.command(
            "floci-final",
            ["docker", "logs", "--tail", "100", SERVER],
            15,
            required=False,
        )
        self.command(
            "control-plane-final",
            ["docker", "logs", "--tail", "100", CONTROL],
            15,
            required=False,
        )

        raw = self.command(
            "guests-final",
            ["docker", "ps", "-a", "--format", "{{.Names}}"],
            15,
            required=False,
        )
        for guest in (raw or "").splitlines():
            if guest.startswith(PREFIX):
                self.command(
                    guest + "-bootstrap-final",
                    [
                        "docker",
                        "exec",
                        guest,
                        "journalctl",
                        "-u",
                        "native-nodeadm",
                        "-u",
                        "kubelet",
                        "--no-pager",
                        "-n",
                        "150",
                    ],
                    20,
                    required=False,
                )

    def cleanup(self):
        failures = []
        # Do not depend on a healthy Kubernetes API to reclaim this run's resources.
        raw = self.aws("cleanup-describe", "ec2", "describe-instances", required=False)
        if raw:
            ids = [
                i["InstanceId"]
                for r in json.loads(raw)["Reservations"]
                for i in r["Instances"]
                if i["State"]["Name"] != "terminated"
            ]
            if ids:
                self.aws(
                    "cleanup-instances",
                    "ec2",
                    "terminate-instances",
                    "--instance-ids",
                    *ids,
                    required=False,
                )
        self.aws(
            "cleanup-cluster",
            "eks",
            "delete-cluster",
            "--name",
            "native-e2e",
            required=False,
        )
        raw = self.command(
            "cleanup-inventory",
            ["docker", "ps", "-a", "--format", "{{.Names}}"],
            20,
            required=False,
        )
        if raw is None:
            return ["Cannot inventory Docker containers"]
        names = [
            n
            for n in raw.splitlines()
            if n in [SERVER, CONTROL] or n.startswith(PREFIX)
        ]
        # Names were absent at preflight; only these fixture names are eligible.
        for name in sorted(names, key=lambda n: n == SERVER):
            self.command(
                "cleanup-" + name,
                ["docker", "rm", "-f", "-v", name],
                45,
                required=False,
            )
        # Floci deletion is asynchronous. An already-removing response is not a leak;
        # judge cleanup by final absence, never by the racing rm response alone.
        remaining = names
        for attempt in range(15):
            raw = self.command(
                "cleanup-confirm-containers",
                ["docker", "ps", "-a", "--format", "{{.Names}}"],
                10,
                required=False,
            )
            if raw is not None:
                remaining = [
                    n
                    for n in raw.splitlines()
                    if n in [SERVER, CONTROL] or n.startswith(PREFIX)
                ]
                if not remaining:
                    break
            time.sleep(2)
        else:
            failures.append("Cannot confirm container removal: " + ", ".join(remaining))
        for net in NETWORKS:
            raw = self.command(
                "cleanup-inspect-" + net,
                ["docker", "network", "inspect", net],
                15,
                required=False,
            )
            if raw:
                if json.loads(raw)[0]["Containers"]:
                    failures.append("Network still attached: " + net)
                elif (
                    self.command(
                        "cleanup-network-" + net,
                        ["docker", "network", "rm", net],
                        15,
                        required=False,
                    )
                    is None
                ):
                    failures.append(net)
        raw = self.command(
            "cleanup-confirm-networks",
            ["docker", "network", "ls", "--format", "{{.Name}}"],
            15,
            required=False,
        )
        if raw is None:
            failures.append("Cannot confirm network removal")
        else:
            failures.extend(net for net in NETWORKS if net in raw.splitlines())
        return failures

    def execute(self):
        print("Evidence: " + str(self.out), flush=True)
        result = {"passed": False, "recoveryInterventions": [], "run": self.name}
        lock = ROOT / "target/eks-native/run.lock"
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        try:
            self.preflight()
            self.prepare()
            if self.args.preflight_only:
                result["preflightPassed"] = True
            else:
                print("Preflight passed; provisioning fresh cluster", flush=True)
                self.exercise()
                result["passed"] = True
        except Exception as error:
            result["error"] = str(error)
            print(str(error), file=sys.stderr, flush=True)
        finally:
            try:
                if self.owned:
                    try:
                        self.diagnostics()
                    except Exception as error:
                        result["diagnosticError"] = str(error)
                    finally:
                        result["cleanupFailures"] = self.cleanup()
            except Exception as error:
                result["cleanupFailures"] = [str(error)]
            result["seconds"] = round(time.monotonic() - self.started, 1)
            result["stages"] = self.records
            self.save("result.json", result)
            lock.unlink()
        print(
            json.dumps({k: v for k, v in result.items() if k != "stages"}, indent=2),
            flush=True,
        )
        return (
            0
            if (result["passed"] or result.get("preflightPassed"))
            and not result.get("cleanupFailures")
            else 1
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="floci/eks-native-test:local")
    parser.add_argument("--revision", required=True)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=ROOT / "target/ami-images/al2023-arm64/image-catalog.yaml",
    )
    parser.add_argument("--min-host-gib", type=int, default=12)
    parser.add_argument("--preflight-only", action="store_true")
    sys.exit(Run(parser.parse_args()).execute())
