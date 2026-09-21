import json, subprocess, time
from pathlib import Path

out = Path("target/eks-native/evidence")
base = [
    "kubectl",
    "--kubeconfig",
    "tools/eks-native/templates/kubeconfig",
    "--request-timeout=10s",
]


def run(args, timeout=60):
    for attempt in range(3):
        try:
            return subprocess.run(
                args, check=True, capture_output=True, text=True, timeout=timeout
            ).stdout
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            with (out / "accept-retries.log").open("a") as f:
                f.write(type(error).__name__ + " " + str(args[:4]) + "\n")
                detail = getattr(error, "stderr", "") or ""
                f.write(
                    detail.decode(errors="replace")
                    if isinstance(detail, bytes)
                    else detail
                )
            if attempt == 2:
                raise
            time.sleep(2)


def get(*args):
    return json.loads(run(base + [*args, "-o", "json"]))


start = time.time()
deadline = start + 480
(out / "csr-approval-final.log").write_text("")
while time.time() < deadline:
    claims = get("get", "nodeclaims")["items"]
    if claims and all(c.get("status", {}).get("nodeName") for c in claims):
        run(["python3", "tools/eks-native/templates/connect-control-plane.py"])
        result = run(["python3", "tools/eks-native/templates/approve-serving-csr.py"])
        if result:
            with (out / "csr-approval-final.log").open("a") as f:
                f.write(result)
        pod = get("-n", "native-e2e", "get", "pod", "native-e2e")
        ready = lambda conditions: any(
            c["type"] == "Ready" and c["status"] == "True" for c in conditions
        )
        if all(ready(c["status"].get("conditions", [])) for c in claims) and ready(
            pod.get("status", {}).get("conditions", [])
        ):
            for claim in claims:
                conditions = {
                    c["type"]: c["status"] for c in claim["status"]["conditions"]
                }
                assert all(
                    conditions.get(name) == "True"
                    for name in ["Launched", "Registered", "Initialized", "Ready"]
                )
            node = get("get", "node", claims[0]["status"]["nodeName"])
            assert node["metadata"]["labels"]["kubernetes.io/arch"] == "arm64"
            assert pod["spec"]["nodeName"] == node["metadata"]["name"]
            assert node["spec"]["providerID"] == claims[0]["status"]["providerID"]
            assert ready(node["status"]["conditions"])
            serving = get("get", "csr")["items"]
            if not any(
                c["spec"]["username"] == "system:node:" + node["metadata"]["name"]
                and c.get("status", {}).get("certificate")
                and c["metadata"]["creationTimestamp"]
                >= node["metadata"]["creationTimestamp"]
                for c in serving
            ):
                time.sleep(2)
                continue
            logs = run(
                base
                + ["-n", "native-e2e", "logs", "native-e2e", "--request-timeout=15s"]
            )
            network = run(
                base
                + [
                    "-n",
                    "native-e2e",
                    "exec",
                    "native-e2e",
                    "--",
                    "sh",
                    "-ec",
                    "nslookup network-target.native-e2e.svc.cluster.local; wget -T 15 -qO- http://network-target.native-e2e.svc.cluster.local:8080",
                ],
                timeout=30,
            )
            assert "native-worker-running" in logs
            assert "native-e2e-cross-node-ok" in network
            (out / "workload.log").write_text(logs)
            (out / "network.log").write_text(network)
            (out / "acceptance.json").write_text(
                json.dumps(
                    {
                        "passed": True,
                        "architecture": "arm64",
                        "nodeclaim": claims[0]["metadata"]["name"],
                        "node": node["metadata"]["name"],
                        "nodeUID": node["metadata"]["uid"],
                        "providerID": node["spec"]["providerID"],
                        "workloadUID": pod["metadata"]["uid"],
                        "seconds": round(time.time() - start, 1),
                        "checks": [
                            "Karpenter launch",
                            "automatic nodeadm",
                            "Registered",
                            "Initialized",
                            "NodeClaim Ready",
                            "Node Ready",
                            "workload Running",
                            "Kubernetes logs",
                            "Kubernetes exec",
                            "cluster DNS",
                            "cross-node Service HTTP",
                        ],
                    },
                    indent=2,
                )
            )
            print(
                "PASS: fresh native ARM64 worker, Karpenter Ready, workload, DNS and cross-node HTTP"
            )
            break
    time.sleep(5)
else:
    raise RuntimeError("End-to-end acceptance timed out after 480 seconds")
run(["python3", "tools/eks-native/templates/snapshot.py"])
