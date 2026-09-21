#!/usr/bin/env bash
set -euo pipefail
docker network inspect floci-native-e2e >/dev/null 2>&1 || docker network create floci-native-e2e
docker run -d --name floci-native-e2e --user root --network floci-native-e2e --no-healthcheck \
 -p 4568:4568 -p 9171:9171 -v /var/run/docker.sock:/var/run/docker.sock \
 -v "${EKS_NATIVE_CATALOG}:/etc/floci/eks-native-catalog.yaml:ro" \
 -e FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH=/etc/floci/eks-native-catalog.yaml \
 -e FLOCI_PORT=4568 -e FLOCI_BASE_URL=http://floci-native-e2e:4568 -e FLOCI_HOSTNAME=floci-native-e2e \
 -e FLOCI_DOCKER_RESOURCE_NAMESPACE=eksnative -e FLOCI_STORAGE_MODE=memory \
 -e FLOCI_SERVICES_DOCKER_NETWORK=floci-native-e2e -e FLOCI_SERVICES_EKS_DOCKER_NETWORK=floci-native-e2e \
 -e FLOCI_SERVICES_EKS_DEFAULT_IMAGE=rancher/k3s:v1.34.1-k3s1 \
 -e FLOCI_SERVICES_EKS_API_SERVER_BASE_PORT=6620 -e FLOCI_SERVICES_EKS_API_SERVER_MAX_PORT=6629 \
 -e FLOCI_SERVICES_EKS_ECR_REGISTRY_MIRROR=false -e FLOCI_SERVICES_ECR_ENABLED=false \
 -e FLOCI_SERVICES_EC2_IMDS_PORT=9171 "${EKS_NATIVE_IMAGE:?Set the Floci test image}"
for ((i=0;i<30;i++)); do
 if curl -fsS --max-time 2 http://localhost:4568/_floci/health >/dev/null; then exit 0; fi
 sleep 1
done
exit 1
