import json, base64, subprocess
from cryptography import x509
from cryptography.x509.oid import NameOID
from certificate_addresses import expected_names, matches_node

base = ["kubectl", "--kubeconfig", "tools/eks-native/templates/kubeconfig"]


def get(*args):
    return json.loads(
        subprocess.run(
            base + [*args, "-o", "json"],
            check=True,
            capture_output=True,
            text=True,
            timeout=20,
        ).stdout
    )


claims = get("get", "nodeclaims")["items"]
nodes = {n["metadata"]["name"]: n for n in get("get", "nodes")["items"]}
owned = {
    c["status"]["nodeName"]
    for c in claims
    if c.get("status", {}).get("nodeName")
    and any(
        x["type"] == "Registered" and x["status"] == "True"
        for x in c["status"]["conditions"]
    )
}
for c in get("get", "csr")["items"]:
    s = c["spec"]
    name = s["username"].removeprefix("system:node:")
    if (
        not s["username"].startswith("system:node:")
        or name not in owned
        or s["signerName"] != "kubernetes.io/kubelet-serving"
        or c.get("status", {}).get("conditions")
    ):
        continue
    node = nodes[name]
    if c["metadata"]["creationTimestamp"] < node["metadata"]["creationTimestamp"]:
        continue
    req = x509.load_pem_x509_csr(base64.b64decode(s["request"]))
    assert req.is_signature_valid
    assert [
        a.value for a in req.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    ] == ["system:node:" + name]
    assert [
        a.value for a in req.subject.get_attributes_for_oid(NameOID.ORGANIZATION_NAME)
    ] == ["system:nodes"]
    assert (
        set(s["usages"]) <= {"digital signature", "key encipherment", "server auth"}
        and "server auth" in s["usages"]
    )
    san = req.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    expected_dns, expected = expected_names(node["status"]["addresses"])
    assert name in expected_dns
    actual_dns = set(san.get_values_for_type(x509.DNSName))
    actual_ips = {str(x) for x in san.get_values_for_type(x509.IPAddress)}
    if not matches_node(name, actual_dns, actual_ips, expected_dns, expected) or len(
        san
    ) != len(actual_dns) + len(actual_ips):
        # Kubelet may request a certificate before the cloud controller settles
        # node addresses. Never approve that request; wait for a matching CSR.
        print(
            f"Waiting for matching CSR: {c['metadata']['name']} DNS={sorted(actual_dns)} IP={sorted(actual_ips)} expected DNS={sorted(expected_dns)} IP={sorted(expected)}"
        )
        continue
    subprocess.run(
        base + ["certificate", "approve", c["metadata"]["name"]], check=True, timeout=20
    )
