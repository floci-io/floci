import subprocess, json, yaml, os
from pathlib import Path

p = Path("tools/eks-native/templates")


def call(args):
    return subprocess.run(
        args, check=True, capture_output=True, text=True, timeout=20
    ).stdout


cp = json.loads(call(["docker", "inspect", "floci-eksnative-eks-native-e2e"]))[0][
    "NetworkSettings"
]["Networks"]["floci-native-e2e"]["IPAddress"]
f = json.loads(call(["docker", "inspect", "floci-native-e2e"]))[0]["NetworkSettings"][
    "Networks"
]["floci-native-e2e"]["IPAddress"]
env = dict(
    os.environ,
    AWS_ACCESS_KEY_ID="000000000000",
    AWS_SECRET_ACCESS_KEY="test",
    AWS_DEFAULT_REGION="us-east-1",
)
vpc = subprocess.run(
    [
        "aws",
        "--endpoint-url",
        "http://localhost:4568",
        "ec2",
        "describe-subnets",
        "--subnet-ids",
        "subnet-default-us-east-1-a",
        "--query",
        "Subnets[0].VpcId",
        "--output",
        "text",
    ],
    env=env,
    check=True,
    capture_output=True,
    text=True,
).stdout.strip()
config = f"""[Global]
Zone = us-east-1a
Region = us-east-1
VPC = {vpc}
KubernetesClusterID = native-e2e
KubernetesClusterTag = native-e2e
[ServiceOverride "ec2"]
Service = ec2
Region = us-east-1
URL = http://{f}:4568
SigningRegion = us-east-1
SigningMethod = v4
"""
md = {"name": "native-aws-ccm", "namespace": "kube-system"}
docs = [
    {"apiVersion": "v1", "kind": "ServiceAccount", "metadata": md},
    {
        "apiVersion": "rbac.authorization.k8s.io/v1",
        "kind": "ClusterRoleBinding",
        "metadata": {"name": "native-aws-ccm"},
        "subjects": [{"kind": "ServiceAccount", **md}],
        "roleRef": {
            "apiGroup": "rbac.authorization.k8s.io",
            "kind": "ClusterRole",
            "name": "native-aws-cloud-node-controller",
        },
    },
    {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": md,
        "data": {"cloud.conf": config},
    },
    {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": md,
        "spec": {
            "replicas": 1,
            "selector": {"matchLabels": {"app": "native-aws-ccm"}},
            "template": {
                "metadata": {"labels": {"app": "native-aws-ccm"}},
                "spec": {
                    "nodeSelector": {"floci.test/native-e2e": "true"},
                    "hostNetwork": True,
                    "serviceAccountName": "native-aws-ccm",
                    "tolerations": [{"operator": "Exists"}],
                    "containers": [
                        {
                            "name": "ccm",
                            "image": "registry.k8s.io/provider-aws/cloud-controller-manager:v1.34.3@sha256:ab45cec4a1ee68a556d09a362746db8fd66b258101aefcf0542f51e960248810",
                            "args": [
                                "--cloud-provider=aws",
                                "--cloud-config=/etc/aws/cloud.conf",
                                "--controllers=cloud-node",
                                "--configure-cloud-routes=false",
                                "--leader-elect=false",
                                "--use-service-account-credentials=false",
                                "--v=2",
                            ],
                            "env": [
                                {"name": n, "value": v}
                                for n, v in {
                                    "KUBERNETES_SERVICE_HOST": cp,
                                    "KUBERNETES_SERVICE_PORT": "6443",
                                    "AWS_REGION": "us-east-1",
                                    "AWS_EC2_METADATA_SERVICE_ENDPOINT": f"http://{f}:9171",
                                    "AWS_ENDPOINT_URL": f"http://{f}:4568",
                                }.items()
                            ],
                            "volumeMounts": [
                                {"name": "config", "mountPath": "/etc/aws"}
                            ],
                        }
                    ],
                    "volumes": [
                        {"name": "config", "configMap": {"name": "native-aws-ccm"}}
                    ],
                },
            },
        },
    },
]
docs[-1]["kind"] = "DaemonSet"
docs[-1]["spec"].pop("replicas", None)
(p / "aws-ccm.yaml").write_text(yaml.safe_dump_all(docs, sort_keys=False))
