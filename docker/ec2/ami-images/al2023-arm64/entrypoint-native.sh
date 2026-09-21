#!/usr/bin/env bash
set -euo pipefail
# Floci sets these before attaching the guest networks and registering IMDS.
# Pass endpoint configuration, but never its compatibility test keys, to systemd.
mkdir -p /etc/eks/nodeadm.d /etc/systemd/system/native-nodeadm.service.d
cat > /etc/eks/nodeadm.d/local-runtime.yaml <<EOF
apiVersion: node.eks.aws/v1alpha1
kind: NodeConfig
spec:
  instance:
    environment:
      default:
        AWS_EC2_METADATA_SERVICE_ENDPOINT: "${AWS_EC2_METADATA_SERVICE_ENDPOINT}"
        AWS_ENDPOINT_URL: "${AWS_ENDPOINT_URL}"
        AWS_REGION: "${AWS_REGION}"
        AWS_MAX_ATTEMPTS: "3"
  containerd:
    config: |
      [plugins.'io.containerd.cri.v1.images']
      snapshotter = "native"
  kubelet:
    config:
      failSwapOn: false
EOF
cat > /etc/systemd/system/native-nodeadm.service.d/endpoints.conf <<EOF
[Service]
Environment="AWS_EC2_METADATA_SERVICE_ENDPOINT=${AWS_EC2_METADATA_SERVICE_ENDPOINT}"
Environment="AWS_ENDPOINT_URL=${AWS_ENDPOINT_URL}"
Environment="AWS_REGION=${AWS_REGION}"
Environment="AWS_MAX_ATTEMPTS=3"
EOF
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
exec "$@"
