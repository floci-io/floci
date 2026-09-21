import os, json, subprocess, yaml
from pathlib import Path

p = Path("tools/eks-native/templates")
out = Path("target/eks-native/evidence")


def docker(*args):
    return subprocess.run(
        ["docker", *args], check=True, capture_output=True, text=True, timeout=30
    ).stdout


cp = json.loads(docker("inspect", "floci-eksnative-eks-native-e2e"))[0][
    "NetworkSettings"
]["Networks"]["floci-native-e2e"]["IPAddress"]
f = json.loads(docker("inspect", "floci-native-e2e"))[0]["NetworkSettings"]["Networks"][
    "floci-native-e2e"
]["IPAddress"]
cfg = yaml.safe_load(
    docker("exec", "floci-eksnative-eks-native-e2e", "cat", "/etc/rancher/k3s/k3s.yaml")
)
cfg["clusters"][0]["cluster"]["server"] = "https://localhost:6620"
(p / "kubeconfig").write_text(yaml.safe_dump(cfg))
os.chmod(p / "kubeconfig", 0o600)
vals = {
    "settings": {
        "clusterName": "native-e2e",
        "clusterEndpoint": f"https://{cp}:6443",
        "eksControlPlane": True,
        "isolatedVPC": True,
    },
    "replicas": 1,
    "nodeSelector": {"node-role.kubernetes.io/control-plane": "true"},
    "controller": {
        "image": {
            "tag": "1.8.8",
            "digest": "sha256:36e1c50402eab7aafa7c3b8bba293e66b1f95ad5f211c67fd91c5169db66798c",
        },
        "resources": {
            "requests": {"cpu": "100m", "memory": "256Mi"},
            "limits": {"cpu": "500m", "memory": "512Mi"},
        },
        "env": [
            {"name": n, "value": v}
            for n, v in {
                "AWS_REGION": "us-east-1",
                "AWS_DEFAULT_REGION": "us-east-1",
                "AWS_ACCESS_KEY_ID": "000000000000",
                "AWS_SECRET_ACCESS_KEY": "test",
                "AWS_EC2_METADATA_DISABLED": "true",
                **{
                    n: f"http://{f}:4568"
                    for n in [
                        "AWS_ENDPOINT_URL",
                        "AWS_ENDPOINT_URL_EC2",
                        "AWS_ENDPOINT_URL_EKS",
                        "AWS_ENDPOINT_URL_IAM",
                        "AWS_ENDPOINT_URL_PRICING",
                        "AWS_ENDPOINT_URL_SSM",
                        "AWS_ENDPOINT_URL_STS",
                        "AWS_ENDPOINT_URL_SQS",
                    ]
                },
            }.items()
        ],
    },
    "dnsPolicy": "Default",
    "tolerations": [{"operator": "Exists"}],
    "topologySpreadConstraints": [],
}
(p / "karpenter-values.yaml").write_text(yaml.safe_dump(vals))
# Flannel hostNetwork pods must reach the API before kube-proxy creates Service routes.
docs = list(yaml.safe_load_all((p / "flannel.yaml").read_text()))
for d in docs:
    if d["kind"] == "DaemonSet":
        d["spec"]["template"]["spec"]["containers"][0]["env"] += [
            {"name": "KUBERNETES_SERVICE_HOST", "value": cp},
            {"name": "KUBERNETES_SERVICE_PORT", "value": "6443"},
        ]
(p / "flannel-runtime.yaml").write_text(yaml.safe_dump_all(docs, sort_keys=False))
proxyconfig = {
    "apiVersion": "kubeproxy.config.k8s.io/v1alpha1",
    "kind": "KubeProxyConfiguration",
    "mode": "iptables",
    "clusterCIDR": "10.42.0.0/16",
    "clientConnection": {"kubeconfig": "/var/lib/kube-proxy/kubeconfig"},
    "conntrack": {"maxPerCore": 0, "min": 0},
}
proxykube = {
    "apiVersion": "v1",
    "kind": "Config",
    "clusters": [
        {
            "name": "local",
            "cluster": {
                "server": f"https://{cp}:6443",
                "certificate-authority": "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt",
            },
        }
    ],
    "users": [
        {
            "name": "proxy",
            "user": {
                "tokenFile": "/var/run/secrets/kubernetes.io/serviceaccount/token"
            },
        }
    ],
    "contexts": [{"name": "local", "context": {"cluster": "local", "user": "proxy"}}],
    "current-context": "local",
}
docs = [
    {
        "apiVersion": "v1",
        "kind": "ServiceAccount",
        "metadata": {"name": "native-kube-proxy", "namespace": "kube-system"},
    },
    {
        "apiVersion": "rbac.authorization.k8s.io/v1",
        "kind": "ClusterRoleBinding",
        "metadata": {"name": "native-kube-proxy"},
        "roleRef": {
            "apiGroup": "rbac.authorization.k8s.io",
            "kind": "ClusterRole",
            "name": "system:node-proxier",
        },
        "subjects": [
            {
                "kind": "ServiceAccount",
                "name": "native-kube-proxy",
                "namespace": "kube-system",
            }
        ],
    },
    {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": "native-kube-proxy", "namespace": "kube-system"},
        "data": {
            "config.conf": yaml.safe_dump(proxyconfig),
            "kubeconfig": yaml.safe_dump(proxykube),
        },
    },
    {
        "apiVersion": "apps/v1",
        "kind": "DaemonSet",
        "metadata": {"name": "native-kube-proxy", "namespace": "kube-system"},
        "spec": {
            "selector": {"matchLabels": {"app": "native-kube-proxy"}},
            "template": {
                "metadata": {"labels": {"app": "native-kube-proxy"}},
                "spec": {
                    "nodeSelector": {"floci.test/native-e2e": "true"},
                    "serviceAccountName": "native-kube-proxy",
                    "hostNetwork": True,
                    "tolerations": [{"operator": "Exists"}],
                    "containers": [
                        {
                            "name": "proxy",
                            "image": "registry.k8s.io/kube-proxy:v1.34.1",
                            "command": [
                                "/usr/local/bin/kube-proxy",
                                "--config=/var/lib/kube-proxy/config.conf",
                                "--hostname-override=$(NODE_NAME)",
                            ],
                            "env": [
                                {
                                    "name": "NODE_NAME",
                                    "valueFrom": {
                                        "fieldRef": {"fieldPath": "spec.nodeName"}
                                    },
                                }
                            ],
                            "securityContext": {"privileged": True},
                            "volumeMounts": [
                                {"name": "config", "mountPath": "/var/lib/kube-proxy"},
                                {"name": "lock", "mountPath": "/run/xtables.lock"},
                            ],
                        }
                    ],
                    "volumes": [
                        {"name": "config", "configMap": {"name": "native-kube-proxy"}},
                        {
                            "name": "lock",
                            "hostPath": {
                                "path": "/run/xtables.lock",
                                "type": "FileOrCreate",
                            },
                        },
                    ],
                },
            },
        },
    },
]
(p / "kube-proxy.yaml").write_text(yaml.safe_dump_all(docs, sort_keys=False))
print("Prepared isolated kubeconfig, Karpenter and native networking manifests")
