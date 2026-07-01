# EC2

**Protocol:** EC2 Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## Instance Execution Model

`RunInstances` launches a **real Docker container** for each instance. By default, the container is kept alive with `tail -f /dev/null` so any base image works regardless of its default CMD. Catalog entries that opt into the `systemd` guest runtime start `/sbin/init` instead, with the Docker mounts needed for a systemd-based cloud-image guest.

| EC2 state | Docker operation |
|---|---|
| `pending → running` | Container created and started |
| `running → stopping → stopped` | `docker stop` (30 s timeout, then SIGKILL) |
| `stopped → pending → running` | `docker start` |
| `running → shutting-down → terminated` | `docker rm -f` |
| Reboot | `docker restart` |

Terminated instances remain queryable for 1 hour (matching real EC2 tombstone behavior) before being pruned.

## AMI to Docker Image Mapping

Floci resolves AMI IDs to Docker images from the EC2 image catalog at
`src/main/resources/ec2/image-catalog.yaml`. The same catalog stores the
fallback Docker image, per-AMI Docker image mappings, and `DescribeImages`
metadata.

| AMI ID | Aliases | Docker image |
|---|---|---|
| `ami-0abcdef1234567890` | `ami-amazonlinux2` | `public.ecr.aws/amazonlinux/amazonlinux:2` |
| `ami-0abcdef1234567891` | `ami-amazonlinux2023` | `public.ecr.aws/amazonlinux/amazonlinux:2023` |
| `ami-0abcdef1234567892` | `ami-ubuntu2004` | `public.ecr.aws/docker/library/ubuntu:20.04` |
| `ami-ubuntu2204` | | `public.ecr.aws/docker/library/ubuntu:22.04` |
| `ami-ubuntu2404-arm64` | `ami-ubuntu2404` | `public.ecr.aws/docker/library/ubuntu:24.04` |
| `ami-ubuntu2404-amd64` | | `public.ecr.aws/docker/library/ubuntu:24.04` |
| `ami-ubuntu2404-cloud-arm64` | `ami-ubuntu2404-cloud` | `floci/ami-ubuntu:24.04-arm64` |
| `ami-debian12` | | `public.ecr.aws/docker/library/debian:12` |
| `ami-alpine` | | `public.ecr.aws/docker/library/alpine:latest` |
| `ami-0abcdef1234567893` | | `public.ecr.aws/amazonlinux/amazonlinux:2023` |

Any unrecognized AMI ID (including real AWS AMI IDs like `ami-0abc12345678`) falls back to the catalog `defaultDockerImage` (`public.ecr.aws/amazonlinux/amazonlinux:2023` by default).

### Cloud-image-derived AMI guests

The `ami-ubuntu2404-cloud` entry is an experimental Ubuntu 24.04 guest image built from Canonical cloud-image artifacts, not from the Docker-library `ubuntu:24.04` image. It is intended for EC2 workflows that need packages such as `systemd` and `cloud-init` to match a real Ubuntu cloud image more closely.

This mode is opt-in by AMI selection, not by a global configuration switch.
Existing catalog entries, including `ami-ubuntu2404`, keep their current
Docker-library image mapping and default `tail -f /dev/null` container
lifecycle. The cloud-image-derived entry is a separate AMI ID and alias, so
`DescribeImages` can advertise it while existing callers continue to get the
old behavior unless they choose `ami-ubuntu2404-cloud-arm64` or the
`ami-ubuntu2404-cloud` alias.

The Java metadata-driven builder lives at `io.github.hectorvent.floci.tools.ami.AmiImageTool`. Its recipe is checked in at `docker/ec2/ami-images/image-build-metadata.yaml`, and generated context/provenance defaults to `target/ami-images/<image-id>/`.

```bash
./mvnw -q -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.hectorvent.floci.tools.ami.AmiImageTool \
  -Dexec.args="plan --image-id ubuntu-24.04-arm64"

./mvnw -q -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.hectorvent.floci.tools.ami.AmiImageTool \
  -Dexec.args="generate --image-id ubuntu-24.04-arm64"

./mvnw -q -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.hectorvent.floci.tools.ami.AmiImageTool \
  -Dexec.args="build --image-id ubuntu-24.04-arm64"

./mvnw -q -DskipTests compile exec:java \
  -Dexec.mainClass=io.github.hectorvent.floci.tools.ami.AmiImageTool \
  -Dexec.args="smoke --image-id ubuntu-24.04-arm64"
```

## SSH Key Injection

If `KeyName` is specified at launch, Floci looks up the stored key pair's public key material (set via `ImportKeyPair`) and copies it into `/root/.ssh/authorized_keys` inside the container at boot. It then attempts to start `sshd` if present. The SSH port (container port 22) is mapped to a host port from the configured range (default 2200–2299).

Key pairs created with `CreateKeyPair` contain dummy private key material. Import a real key pair with `ImportKeyPair` to enable working SSH access.

## Security Group Port Publishing

When an instance's security groups open a TCP port to a CIDR source, Floci publishes that port on the host so you can reach the app from `localhost`. For each opened port Floci starts a small `alpine/socat` sidecar container that binds an allocated host port (default range 30000–30999) and forwards it to the instance container's IP. This works both for rules present at launch and for rules added later with `authorize-security-group-ingress`; revoking the rule removes the forward. The mapping (`app port -> host port`) is written to the logs:

```
Published EC2 instance i-0abc... app port 80 on host port 30000 (socat -> 172.17.0.3:80)
```

Notes and limitations:

- The app inside the instance must listen on `0.0.0.0` (not `127.0.0.1`) for the forward to reach it.
- Only CIDR-sourced TCP rules are published. A port opened only to a referenced security group (or via a prefix list) is not published, matching AWS: those grant reachability from the referenced group's private IPs, not from the host. The source CIDR value itself is not enforced, so a CIDR-sourced port is reachable whether the rule is `0.0.0.0/0` or narrower.
- Ports are aggregated across all of the instance's security groups, SSH (22) is never re-forwarded, and any single rule whose port span exceeds `max-published-ports-per-instance` (default 20) is skipped so an allow-all range cannot spawn thousands of sidecars. The total published per instance is capped at the same limit.
- Stopping an instance tears down its forwards; starting it again does not automatically restore them (re-run `authorize-security-group-ingress`, or recreate the instance).
- Set `publish-security-group-ports: false` (`FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS=false`) to keep security groups as metadata only.

## UserData

`UserData` must be base64-encoded in the request (matching the AWS wire format). Floci decodes it, copies the script into `/tmp/user-data.sh` inside the container, and executes the script directly after SSH key injection so the script shebang selects the interpreter. Output is captured and logged.

EC2 containers receive `AWS_EC2_METADATA_SERVICE_ENDPOINT` for IMDS and `AWS_ENDPOINT_URL` for AWS service API calls back to Floci.

## Instance Metadata Service (IMDS)

Floci runs an IMDS-compatible HTTP server on port `9169` of the host. Each launched container receives the environment variable `AWS_EC2_METADATA_SERVICE_ENDPOINT` pointing to this server.

Both IMDSv1 (no token) and IMDSv2 (token-based) flows are supported:

```bash
# IMDSv2 — get a token first
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "x-aws-ec2-metadata-token-ttl-seconds: 21600")

# Then use the token for metadata requests
curl -s -H "x-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id
```

### Supported IMDS endpoints

| Endpoint | Returns |
|---|---|
| `GET /latest/meta-data/instance-id` | Instance ID |
| `GET /latest/meta-data/ami-id` | Image ID |
| `GET /latest/meta-data/instance-type` | Instance type |
| `GET /latest/meta-data/local-ipv4` | Private IP |
| `GET /latest/meta-data/public-ipv4` | Public IP (`127.0.0.1`) |
| `GET /latest/meta-data/public-hostname` | Public hostname |
| `GET /latest/meta-data/local-hostname` | Private DNS name |
| `GET /latest/meta-data/hostname` | Private DNS name |
| `GET /latest/meta-data/mac` | MAC address of first ENI |
| `GET /latest/meta-data/security-groups` | Security group names |
| `GET /latest/meta-data/placement/availability-zone` | AZ |
| `GET /latest/meta-data/placement/region` | Region |
| `GET /latest/meta-data/iam/info` | IAM instance profile info |
| `GET /latest/meta-data/iam/security-credentials/` | Role name list |
| `GET /latest/meta-data/iam/security-credentials/{role}` | Temporary credentials |
| `GET /latest/user-data` | UserData script |
| `GET /latest/dynamic/instance-identity/document` | Identity document JSON |

IAM credentials are served when the instance has an `IamInstanceProfile.Arn` set at launch. The container can then call other Floci services with full SigV4 validation using the standard AWS SDK credential chain.

## Default Resources

Floci seeds the following resources on first use in each region so Terraform, the AWS CLI, and SDK clients work out of the box without any setup:

| Resource | ID | Details |
|---|---|---|
| Default VPC | `vpc-default` | CIDR `172.31.0.0/16` |
| Default Subnet (AZ a) | `subnet-default-a` | CIDR `172.31.0.0/20` |
| Default Subnet (AZ b) | `subnet-default-b` | CIDR `172.31.16.0/20` |
| Default Subnet (AZ c) | `subnet-default-c` | CIDR `172.31.32.0/20` |
| Default Security Group | `sg-default` | `groupName=default`, all-traffic egress |
| Default Internet Gateway | `igw-default` | Attached to default VPC |
| Main Route Table | `rtb-default` | Associated with default VPC |
| Default Network ACL | `acl-default` | Allow-all, associated with the default subnets |

## Supported Actions

### Instances

| Action | Description |
|--------|-------------|
| RunInstances | - |
| DescribeInstances | - |
| TerminateInstances | - |
| StartInstances | - |
| StopInstances | - |
| RebootInstances | - |
| DescribeInstanceStatus | - |
| DescribeInstanceAttribute | - |
| ModifyInstanceAttribute | - |

### VPCs

| Action | Description |
|--------|-------------|
| CreateVpc | - |
| DescribeVpcs | - |
| DeleteVpc | - |
| ModifyVpcAttribute | - |
| DescribeVpcAttribute | - |
| DescribeVpcEndpointServices | - |
| CreateVpcEndpoint | - |
| DescribeVpcEndpoints | - |
| DeleteVpcEndpoints | - |
| CreateDefaultVpc | - |
| AssociateVpcCidrBlock | - |
| DisassociateVpcCidrBlock | - |

### Subnets

| Action | Description |
|--------|-------------|
| CreateSubnet | - |
| DescribeSubnets | - |
| DeleteSubnet | - |
| ModifySubnetAttribute | - |

### Security Groups

| Action | Description |
|--------|-------------|
| CreateSecurityGroup | - |
| DescribeSecurityGroups | - |
| DeleteSecurityGroup | - |
| AuthorizeSecurityGroupIngress | - |
| AuthorizeSecurityGroupEgress | - |
| RevokeSecurityGroupIngress | - |
| RevokeSecurityGroupEgress | - |
| DescribeSecurityGroupRules | - |
| ModifySecurityGroupRules | - |
| UpdateSecurityGroupRuleDescriptionsIngress | - |
| UpdateSecurityGroupRuleDescriptionsEgress | - |

### Key Pairs

| Action | Description |
|--------|-------------|
| CreateKeyPair | - |
| DescribeKeyPairs | - |
| DeleteKeyPair | - |
| ImportKeyPair | - |

### AMIs

| Action | Description |
|--------|-------------|
| DescribeImages | - |

### Tags

| Action | Description |
|--------|-------------|
| CreateTags | - |
| DeleteTags | - |
| DescribeTags | - |

### Internet Gateways

| Action | Description |
|--------|-------------|
| CreateInternetGateway | - |
| DescribeInternetGateways | - |
| DeleteInternetGateway | - |
| AttachInternetGateway | - |
| DetachInternetGateway | - |

### Route Tables

| Action | Description |
|--------|-------------|
| CreateRouteTable | - |
| DescribeRouteTables | - |
| DeleteRouteTable | - |
| AssociateRouteTable | - |
| DisassociateRouteTable | - |
| CreateRoute | - |
| DeleteRoute | - |

### Network ACLs

| Action | Description |
|--------|-------------|
| CreateNetworkAcl | - |
| DescribeNetworkAcls | - |
| DeleteNetworkAcl | - |
| CreateNetworkAclEntry | - |
| ReplaceNetworkAclEntry | - |
| DeleteNetworkAclEntry | - |
| ReplaceNetworkAclAssociation | - |

### Prefix Lists

| Action | Description |
|--------|-------------|
| DescribePrefixLists | - |

### NAT Gateways

| Action | Description |
|--------|-------------|
| CreateNatGateway | - |
| DescribeNatGateways | - |
| DeleteNatGateway | - |

### Elastic IPs

| Action | Description |
|--------|-------------|
| AllocateAddress | - |
| DescribeAddresses | - |
| DescribeAddressesAttribute | - |
| AssociateAddress | - |
| DisassociateAddress | - |
| ReleaseAddress | - |

### Availability Zones & Regions

| Action | Description |
|--------|-------------|
| DescribeAvailabilityZones | - |
| DescribeRegions | - |
| DescribeAccountAttributes | - |

### Instance Types

| Action | Description |
|--------|-------------|
| DescribeInstanceTypes | - |
| DescribeInstanceTypeOfferings | - |

### Launch Templates

| Action | Description |
|--------|-------------|
| CreateLaunchTemplate | - |
| CreateLaunchTemplateVersion | - |
| DescribeLaunchTemplates | - |
| DescribeLaunchTemplateVersions | - |
| ModifyLaunchTemplate | - |
| DeleteLaunchTemplate | - |

Launch templates store versioned launch data. New template versions can be created from an existing source version, and `ModifyLaunchTemplate` updates the default version used by later launches.

### IAM Instance Profiles

| Action | Description |
|--------|-------------|
| DescribeIamInstanceProfileAssociations | - |

### Network Interfaces

| Action | Description |
|--------|-------------|
| DescribeNetworkInterfaces | - |

### Volumes

| Action | Description |
|--------|-------------|
| CreateVolume | - |
| DescribeVolumes | - |
| DeleteVolume | - |

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EC2_IMDS_PORT` | `9169` | Host port for the IMDS server |
| `FLOCI_SERVICES_EC2_SSH_PORT_RANGE_START` | `2200` | Start of SSH host port range |
| `FLOCI_SERVICES_EC2_SSH_PORT_RANGE_END` | `2299` | End of SSH host port range |
| `FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS` | `true` | Publish security-group TCP ingress ports on the host via socat sidecars |
| `FLOCI_SERVICES_EC2_APP_PORT_RANGE_START` | `30000` | Start of the host-port range for published app ports |
| `FLOCI_SERVICES_EC2_APP_PORT_RANGE_END` | `30999` | End of the host-port range for published app ports |
| `FLOCI_SERVICES_EC2_MAX_PUBLISHED_PORTS_PER_INSTANCE` | `20` | Max published ports per instance; also the widest single-rule span published |
| `FLOCI_SERVICES_EC2_SOCAT_IMAGE` | `alpine/socat` | Image used for the port-forwarding sidecar |
| `FLOCI_SERVICES_EC2_MOCK` | `false` | Skip Docker; instances jump directly to final state (useful for tests) |

## Requirements

EC2 requires the Docker socket to be accessible (same as Lambda, ECS, and other container services):

```yaml
services:
  floci:
    image: floci/floci:latest
    ports:
      - "4566:4566"
      - "9169:9169"   # IMDS — expose if containers need to reach it externally
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

The IMDS port (`9169`) only needs to be published if you are running EC2 containers outside the default Docker bridge network.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Import an SSH key pair for injection at launch
aws ec2 import-key-pair \
  --key-name my-key \
  --public-key-material fileb://~/.ssh/id_rsa.pub \
  --endpoint-url $AWS_ENDPOINT_URL

# Launch a real Docker container instance with UserData
aws ec2 run-instances \
  --image-id ami-amazonlinux2023 \
  --instance-type t2.micro \
  --min-count 1 \
  --max-count 1 \
  --key-name my-key \
  --user-data '#!/bin/bash
yum install -y nginx
systemctl start nginx' \
  --endpoint-url $AWS_ENDPOINT_URL

# Launch with an IAM instance profile (credentials served via IMDS)
aws ec2 run-instances \
  --image-id ami-amazonlinux2023 \
  --instance-type t2.micro \
  --min-count 1 \
  --max-count 1 \
  --iam-instance-profile Arn=arn:aws:iam::000000000000:instance-profile/my-app-role \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe running instances
aws ec2 describe-instances \
  --filters "Name=instance-state-name,Values=running" \
  --endpoint-url $AWS_ENDPOINT_URL

# Stop and start an instance
aws ec2 stop-instances --instance-ids i-XXXXX --endpoint-url $AWS_ENDPOINT_URL
aws ec2 start-instances --instance-ids i-XXXXX --endpoint-url $AWS_ENDPOINT_URL

# Terminate an instance
aws ec2 terminate-instances --instance-ids i-XXXXX --endpoint-url $AWS_ENDPOINT_URL

# Create a VPC and subnet
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --endpoint-url $AWS_ENDPOINT_URL
aws ec2 create-subnet --vpc-id vpc-XXXXX --cidr-block 10.0.1.0/24 --endpoint-url $AWS_ENDPOINT_URL

# Create and configure a security group
aws ec2 create-security-group \
  --group-name my-sg \
  --description "My security group" \
  --vpc-id vpc-XXXXX \
  --endpoint-url $AWS_ENDPOINT_URL

aws ec2 authorize-security-group-ingress \
  --group-id sg-XXXXX \
  --protocol tcp \
  --port 22 \
  --cidr 0.0.0.0/0 \
  --endpoint-url $AWS_ENDPOINT_URL

# Allocate and associate an Elastic IP
aws ec2 allocate-address --domain vpc --endpoint-url $AWS_ENDPOINT_URL
aws ec2 associate-address \
  --allocation-id eipalloc-XXXXX \
  --instance-id i-XXXXX \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Notes

- `DescribeImages` returns AMIs from the EC2 image catalog, including common AMIs and Floci-native AMI IDs.
- Key material returned by `CreateKeyPair` is a dummy RSA PEM — not usable for real SSH. Use `ImportKeyPair` for working SSH access.
- Security group rules are not enforced as a firewall (Docker bridge networking handles routing), but TCP ingress rules opened to a CIDR source are published on the host via socat sidecars so the instance's app is reachable from `localhost` — see [Security Group Port Publishing](#security-group-port-publishing).
- The IMDS server identifies which instance is calling via IMDSv2 tokens (mapped at token issuance time) or by the container's bridge IP for IMDSv1.
