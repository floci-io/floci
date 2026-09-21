import json, os, subprocess, time
from pathlib import Path

out = Path("target/eks-native/evidence")
env = dict(
    os.environ,
    AWS_ACCESS_KEY_ID="000000000000",
    AWS_SECRET_ACCESS_KEY="test",
    AWS_DEFAULT_REGION="us-east-1",
)


def aws(*args):
    r = subprocess.run(
        ["aws", "--endpoint-url", "http://localhost:4568", *args],
        env=env,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    return json.loads(r.stdout) if r.stdout.strip() else {}


trust = json.dumps(
    {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {"Service": "ec2.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }
        ],
    }
)
role = aws(
    "iam",
    "create-role",
    "--role-name",
    "native-e2e-node",
    "--assume-role-policy-document",
    trust,
)["Role"]["Arn"]
aws(
    "iam",
    "put-role-policy",
    "--role-name",
    "native-e2e-node",
    "--policy-name",
    "DescribeInstance",
    "--policy-document",
    json.dumps(
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": ["ec2:DescribeInstances"],
                    "Resource": "*",
                }
            ],
        }
    ),
)
aws(
    "iam",
    "create-instance-profile",
    "--instance-profile-name",
    "native-e2e-node",
    "--path",
    "/karpenter/native-e2e/",
)
aws(
    "iam",
    "add-role-to-instance-profile",
    "--instance-profile-name",
    "native-e2e-node",
    "--role-name",
    "native-e2e-node",
)
cluster = aws(
    "eks",
    "create-cluster",
    "--name",
    "native-e2e",
    "--kubernetes-version",
    "1.34",
    "--role-arn",
    role,
    "--access-config",
    "authenticationMode=API,bootstrapClusterCreatorAdminPermissions=false",
    "--resources-vpc-config",
    "subnetIds=subnet-default-us-east-1-a,subnet-default-us-east-1-b",
)["cluster"]
(out / "cluster-create.json").write_text(json.dumps(cluster, indent=2))
for _ in range(45):
    cluster = aws("eks", "describe-cluster", "--name", "native-e2e")["cluster"]
    if cluster.get("status") == "ACTIVE" and cluster.get(
        "certificateAuthority", {}
    ).get("data"):
        break
    time.sleep(2)
else:
    raise RuntimeError("Cluster certificate was not available within 90 seconds")
(out / "cluster.json").write_text(json.dumps(cluster, indent=2))
print("Cluster ready for native worker probe")
