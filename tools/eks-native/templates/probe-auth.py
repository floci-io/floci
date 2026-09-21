import subprocess, json, base64, urllib.parse, urllib.request, yaml
from pathlib import Path


def run(*args):
    return subprocess.check_output(args, text=True, timeout=30)


config = yaml.safe_load(
    run(
        "docker",
        "exec",
        "floci-eksnative-eks-native-e2e",
        "cat",
        "/etc/token-webhook.yaml",
    )
)
url = config["clusters"][0]["cluster"]["server"]
url = (
    "http://localhost:4568"
    + urllib.parse.urlsplit(url).path
    + "?"
    + urllib.parse.urlsplit(url).query
)
claims = json.loads(
    run(
        "kubectl",
        "--kubeconfig",
        "tools/eks-native/templates/kubeconfig",
        "get",
        "nodeclaims",
        "-o",
        "json",
    )
)["items"]
guest = "floci-eksnative-ec2-" + claims[0]["status"]["providerID"].split("/")[-1]
raw = run(
    "docker",
    "exec",
    guest,
    "env",
    "-u",
    "AWS_ACCESS_KEY_ID",
    "-u",
    "AWS_SECRET_ACCESS_KEY",
    "-u",
    "AWS_SESSION_TOKEN",
    "aws",
    "eks",
    "get-token",
    "--cluster-name",
    "native-e2e",
    "--region",
    "us-east-1",
)
token = json.loads(raw)["status"]["token"]
encoded = token.split(".", 1)[1]
parsed = urllib.parse.urlsplit(
    base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode()
)
q = urllib.parse.parse_qs(parsed.query)
result = {
    "tokenHost": parsed.netloc,
    "tokenPath": parsed.path,
    "signedHeaders": q.get("X-Amz-SignedHeaders"),
    "expires": q.get("X-Amz-Expires"),
    "date": q.get("X-Amz-Date"),
    "temporaryKey": q["X-Amz-Credential"][0].startswith("ASIA"),
    "scope": q["X-Amz-Credential"][0].split("/")[1:],
    "hasSessionToken": "X-Amz-Security-Token" in q,
}
req = urllib.request.Request(
    url,
    data=json.dumps(
        {
            "apiVersion": "authentication.k8s.io/v1",
            "kind": "TokenReview",
            "spec": {"token": token},
        }
    ).encode(),
    headers={"Content-Type": "application/json"},
)
result["review"] = json.load(urllib.request.urlopen(req, timeout=10))
import ssl

kcfg = yaml.safe_load(Path("tools/eks-native/templates/kubeconfig").read_text())
ca = base64.b64decode(
    kcfg["clusters"][0]["cluster"]["certificate-authority-data"]
).decode()
request = urllib.request.Request(
    "https://localhost:6620/apis/authentication.k8s.io/v1/selfsubjectreviews",
    data=json.dumps(
        {"apiVersion": "authentication.k8s.io/v1", "kind": "SelfSubjectReview"}
    ).encode(),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
)
try:
    result["apiReview"] = json.load(
        urllib.request.urlopen(
            request, context=ssl.create_default_context(cadata=ca), timeout=15
        )
    )
except urllib.error.HTTPError as e:
    result["apiError"] = {"status": e.code, "body": e.read().decode()}
Path("target/eks-native/evidence/auth-probe.json").write_text(
    json.dumps(result, indent=2)
)
print(json.dumps(result, indent=2))
