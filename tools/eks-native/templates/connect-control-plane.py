import subprocess, json

base = ["kubectl", "--kubeconfig", "tools/eks-native/templates/kubeconfig"]


def call(args):
    return subprocess.run(
        args, check=True, capture_output=True, text=True, timeout=30
    ).stdout


j = json.loads(call(base + ["get", "nodeclaims", "-o", "json"]))["items"][0]
guest = "floci-eksnative-ec2-" + j["status"]["providerID"].split("/")[-1]
networks = json.loads(call(["docker", "inspect", guest]))[0]["NetworkSettings"][
    "Networks"
]
server = json.loads(call(["docker", "inspect", "floci-eksnative-eks-native-e2e"]))[0][
    "NetworkSettings"
]["Networks"]
for net in networks:
    if "vpc" in net and net not in server:
        call(["docker", "network", "connect", net, "floci-eksnative-eks-native-e2e"])
        print("Connected control plane to", net)
