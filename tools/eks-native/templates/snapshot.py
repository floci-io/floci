import subprocess, json
from pathlib import Path

out = Path("target/eks-native/evidence")
base = ["kubectl", "--kubeconfig", "tools/eks-native/templates/kubeconfig"]


def run(args):
    return subprocess.run(
        args, check=True, capture_output=True, text=True, timeout=30
    ).stdout


for name, args in [
    ("nodes", ["get", "nodes", "-o", "json"]),
    ("nodeclaims", ["get", "nodeclaims", "-o", "json"]),
    ("pods", ["get", "pods", "-A", "-o", "json"]),
    ("events", ["get", "events", "-A", "-o", "json"]),
]:
    (out / (name + ".json")).write_text(run(base + args))
claims = json.loads((out / "nodeclaims.json").read_text())["items"]
for claim in claims:
    provider = claim.get("status", {}).get("providerID", "")
    if not provider:
        continue
    guest = "floci-eksnative-ec2-" + provider.split("/")[-1]
    for name, args in [
        ("nodeadm", ["journalctl", "-u", "native-nodeadm", "--no-pager"]),
        ("kubelet", ["journalctl", "-u", "kubelet", "--no-pager", "-n", "100"]),
        (
            "runtime",
            [
                "sh",
                "-c",
                "uname -m; systemctl is-active native-nodeadm containerd kubelet; ip -4 addr; ip route",
            ],
        ),
    ]:
        (out / (name + ".log")).write_text(run(["docker", "exec", guest, *args]))
    (out / "guest-image.txt").write_text(
        run(["docker", "inspect", guest, "--format", "{{.Config.Image}} {{.Image}}"])
    )
(out / "floci-image.txt").write_text(
    run(
        [
            "docker",
            "inspect",
            "floci-native-e2e",
            "--format",
            "{{.Config.Image}} {{.Image}}",
        ]
    )
)
(out / "karpenter.log").write_text(
    run(base + ["-n", "karpenter", "logs", "deployment/karpenter", "--tail=100"])
)
print("Snapshot saved")
