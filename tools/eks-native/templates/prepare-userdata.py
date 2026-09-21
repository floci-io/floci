import base64, yaml, subprocess
from pathlib import Path

p = Path("tools/eks-native/templates")
ca = subprocess.run(
    [
        "docker",
        "exec",
        "floci-eksnative-eks-native-e2e",
        "cat",
        "/var/lib/rancher/k3s/server/tls/client-ca.crt",
    ],
    check=True,
    capture_output=True,
    timeout=20,
).stdout
script = (
    "#!/bin/bash\nset -eu\nmkdir -p /etc/eks\nprintf '%s' '"
    + base64.b64encode(ca).decode()
    + "' | base64 -d > /etc/eks/local-client-ca.crt\n"
)
config = (p / "client-ca.yaml").read_text()
mime = (
    'MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary="LOCALCA"\n\n--LOCALCA\nContent-Type: text/x-shellscript\n\n'
    + script
    + "\n--LOCALCA\nContent-Type: application/node.eks.aws\n\n"
    + config
    + "\n--LOCALCA--\n"
)
docs = list(yaml.safe_load_all((p / "provision.yaml").read_text()))
for d in docs:
    if d["kind"] == "EC2NodeClass":
        d["spec"]["userData"] = mime
(p / "provision-final.yaml").write_text(yaml.safe_dump_all(docs, sort_keys=False))
