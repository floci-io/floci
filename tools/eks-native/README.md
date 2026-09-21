# Native AL2023 worker acceptance (opt-in)

This Linux ARM64 Docker fixture validates real nodeadm, kubelet and containerd boot,
Karpenter provisioning, worker authentication and Kubernetes workload traffic. It is
not enabled in normal tests and does not change Floci's default AL2023 image.

## Prerequisites

- Native ARM64 Docker with privileged containers, systemd/cgroup v2 and Docker socket
  access. Locally validated with OrbStack on Apple Silicon; other runtimes are not yet
  validated. This is not an amd64 emulation test.
- Java 25, Maven wrapper prerequisites, Python 3.11+, Git, Docker, AWS CLI, kubectl,
  Helm and curl. The image build downloads public dependencies; it uses no real AWS account.
- At least 12 GiB host free space, and both 8 GiB and 20% free in Docker. Building from
  scratch needs additional space for the Go toolchain and image layers.
- Ports 4568, 9171 and 6620 available. The runner reserves fixture names and refuses
  existing containers/networks; it never reuses another cluster or prunes disk space.

## Build and run

From the repository root:

```sh
python3 -m venv target/eks-native-venv
. target/eks-native-venv/bin/activate
pip install -r tools/eks-native/requirements.txt
python docker/ec2/ami-images/al2023-arm64/build.py
./mvnw package -DskipTests
docker build -f docker/Dockerfile.jvm-package \
  --label org.opencontainers.image.revision="$(git rev-parse HEAD)" \
  -t floci/eks-native-test:local .
python tools/eks-native/run.py --revision "$(git rev-parse HEAD)"
```

The worker build uses the checked-in `inputs.json`: base/Go/pause image digests,
AL2023 repository release, a nodeadm Git commit, and SHA-256 checked EKS binaries.
The downloaded nodeadm source checkout must be clean. It records the resulting image ID and inputs in
`target/ami-images/al2023-arm64/provenance.json`; installed RPM versions are recorded
inside the image at `/etc/eks/floci-image-packages.txt`. This pins build inputs, not a
claim that Docker layer timestamps or resulting image IDs are bit-for-bit reproducible.
No prebuilt worker image is published by this contribution.

The generated `image-catalog.yaml` contains the bundled entries plus
`ami-floci-al2023-eks-arm64`, selecting the local worker. The runner mounts it read-only
and sets `FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH`. This optional setting replaces the
whole catalog for that Floci process; malformed/missing files fail instead of falling
back silently. Without it, the existing bundled catalog is unchanged.

`--preflight-only` checks prerequisites without creating a cluster. `--image`,
`--revision` and `--catalog` select another Floci build and generated catalog.
Do not relabel an unrelated image as the expected revision: the label is provenance,
not a cryptographic attestation. Rebuild after changing source.

The runner and build helper also have local regression checks:

```sh
python -m unittest discover -s tools/eks-native -p 'test_*.py'
```

## Acceptance and evidence

Each run writes generated private fixtures to `target/eks-native/runs/<timestamp>`
and logs, image IDs, stage timings and results to `target/eks-native/evidence/<timestamp>`.
Review diagnostics before publishing them; kubeconfigs are local credentials.

The runner creates a fresh k3s-backed EKS cluster and installs Karpenter 1.8.8.
A nested-path instance profile and EC2_LINUX access entry exercise the worker's own
IMDS credentials. It asserts automatic nodeadm bootstrap, Registered, Initialized,
NodeClaim Ready, Node Ready, workload scheduling, logs/exec, DNS and cross-node Service
HTTP. Worker tokens must pass both TokenReview and a real Kubernetes SelfSubjectReview.

Commands and retries are bounded. Creation calls are not blindly retried. Failures
retain diagnostics. Cleanup uses the local Floci APIs and an owned-container fallback,
then confirms final absence to handle asynchronous deletion. It removes only fixture
resources absent at preflight, including anonymous volumes of fallback containers and
empty fixture networks. Unrelated resources are untouched. A workspace lock rejects
concurrent runs; inspect resources before removing a lock left by SIGKILL.

A run passes only if acceptance and cleanup pass. The runner never repairs a guest,
clears taints, writes Ready conditions, or changes eviction thresholds. A failed run
must be reported as failed, even if a later run passes.

## Container-specific fixture boundary

The worker is a local AL2023 container, not an EKS-optimized AMI. Its systemd bootstrap
service runs nodeadm against IMDS user data after Floci makes it available, retrying
while metadata is not ready. It does not reproduce EC2/cloud-init's separate
nodeadm-config, user-data and nodeadm-run units. Floci executes shell MIME parts; the
fixture supplies its client CA before node registration. Boot-order parity with an
actual EKS AMI is outside this test's claim.

The entrypoint strips compatibility AWS keys before systemd starts, passes only local
endpoint/region configuration, selects containerd's native snapshotter and allows the
host's swap configuration. Kubelet uses a locally imported pause image. No production
AWS credentials are embedded.

The test installs Flannel (not Amazon VPC CNI), kube-proxy and an AWS cloud-node
controller. It bootstraps the local client CA, validates serving CSRs against the
registered worker identity/address set, and connects the control plane to the worker's
Docker network. One initial k3s restart applies `egress-selector-mode: disabled`; this
is recorded fixture setup, not recovery. These requirements, privileged execution and
component update costs need review before expanding to default CI or distributing images.

The systemd unit layout and bootstrap configuration follow the MIT-licensed
[amazon-eks-ami sources](https://github.com/awslabs/amazon-eks-ami).
The Flannel fixture is adapted from its Apache-2.0
[v0.27.4 manifest](https://github.com/flannel-io/flannel/releases/tag/v0.27.4).
Redistribution of assembled images is a separate maintainer decision.
