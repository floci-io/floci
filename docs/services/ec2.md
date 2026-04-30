# EC2

**Protocol:** EC2 Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

## Instance Execution Model

`RunInstances` launches a **real Docker container** for each instance. The container is kept alive with `tail -f /dev/null` so any base image works regardless of its default CMD. The lifecycle maps directly to Docker:

| EC2 state | Docker operation |
|---|---|
| `pending → running` | Container created and started |
| `running → stopping → stopped` | `docker stop` (30 s timeout, then SIGKILL) |
| `stopped → pending → running` | `docker start` |
| `running → shutting-down → terminated` | `docker rm -f` |
| Reboot | `docker restart` |

Terminated instances remain queryable for 1 hour (matching real EC2 tombstone behavior) before being pruned.

## AMI to Docker Image Mapping

Floci resolves AMI IDs to Docker images. Built-in mappings:

| AMI ID | Docker image |
|---|---|
| `ami-amazonlinux2023` | `public.ecr.aws/amazonlinux/amazonlinux:2023` |
| `ami-amazonlinux2` | `public.ecr.aws/amazonlinux/amazonlinux:2` |
| `ami-ubuntu2204` | `public.ecr.aws/docker/library/ubuntu:22.04` |
| `ami-ubuntu2004` | `public.ecr.aws/docker/library/ubuntu:20.04` |
| `ami-debian12` | `public.ecr.aws/docker/library/debian:12` |
| `ami-alpine` | `public.ecr.aws/docker/library/alpine:latest` |

Any unrecognized AMI ID (including real AWS AMI IDs like `ami-0abc12345678`) falls back to `public.ecr.aws/amazonlinux/amazonlinux:2023`.

## SSH Key Injection

If `KeyName` is specified at launch, Floci looks up the stored key pair's public key material (set via `ImportKeyPair`) and copies it into `/root/.ssh/authorized_keys` inside the container at boot. It then attempts to start `sshd` if present. The SSH port (container port 22) is mapped to a host port from the configured range (default 2200–2299).

Key pairs created with `CreateKeyPair` contain dummy private key material. Import a real key pair with `ImportKeyPair` to enable working SSH access.

## UserData

`UserData` must be base64-encoded in the request (matching the AWS wire format). Floci decodes it, copies the script into `/tmp/user-data.sh` inside the container, and executes it with `sh` after SSH key injection. Output is captured and logged.

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

## Supported Actions

### Instances
`RunInstances` · `DescribeInstances` · `TerminateInstances` · `StartInstances` · `StopInstances` · `RebootInstances` · `DescribeInstanceStatus` · `DescribeInstanceAttribute` · `ModifyInstanceAttribute`

### VPCs
`CreateVpc` · `DescribeVpcs` · `DeleteVpc` · `ModifyVpcAttribute` · `DescribeVpcAttribute` · `CreateDefaultVpc` · `AssociateVpcCidrBlock` · `DisassociateVpcCidrBlock`

### Subnets
`CreateSubnet` · `DescribeSubnets` · `DeleteSubnet` · `ModifySubnetAttribute`

### Security Groups
`CreateSecurityGroup` · `DescribeSecurityGroups` · `DeleteSecurityGroup` · `AuthorizeSecurityGroupIngress` · `AuthorizeSecurityGroupEgress` · `RevokeSecurityGroupIngress` · `RevokeSecurityGroupEgress` · `DescribeSecurityGroupRules` · `ModifySecurityGroupRules` · `UpdateSecurityGroupRuleDescriptionsIngress` · `UpdateSecurityGroupRuleDescriptionsEgress`

### Key Pairs
`CreateKeyPair` · `DescribeKeyPairs` · `DeleteKeyPair` · `ImportKeyPair`

### AMIs
`DescribeImages`

### Tags
`CreateTags` · `DeleteTags` · `DescribeTags`

### Internet Gateways
`CreateInternetGateway` · `DescribeInternetGateways` · `DeleteInternetGateway` · `AttachInternetGateway` · `DetachInternetGateway`

### Route Tables
`CreateRouteTable` · `DescribeRouteTables` · `DeleteRouteTable` · `AssociateRouteTable` · `DisassociateRouteTable` · `CreateRoute` · `DeleteRoute`

### Elastic IPs
`AllocateAddress` · `DescribeAddresses` · `AssociateAddress` · `DisassociateAddress` · `ReleaseAddress`

### Availability Zones & Regions
`DescribeAvailabilityZones` · `DescribeRegions` · `DescribeAccountAttributes`

### Instance Types
`DescribeInstanceTypes`

### Volumes
`CreateVolume` · `DescribeVolumes` · `DeleteVolume`

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EC2_IMDS_PORT` | `9169` | Host port for the IMDS server |
| `FLOCI_SERVICES_EC2_SSH_PORT_RANGE_START` | `2200` | Start of SSH host port range |
| `FLOCI_SERVICES_EC2_SSH_PORT_RANGE_END` | `2299` | End of SSH host port range |
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

- `DescribeImages` returns a static list of common AMIs (Amazon Linux 2, Amazon Linux 2023, Ubuntu 20.04, Windows Server 2022) plus all Floci-native AMI IDs.
- Key material returned by `CreateKeyPair` is a dummy RSA PEM — not usable for real SSH. Use `ImportKeyPair` for working SSH access.
- Security group rules are stored and returned correctly but are not enforced at the network level — Docker bridge networking handles routing.
- The IMDS server identifies which instance is calling via IMDSv2 tokens (mapped at token issuance time) or by the container's bridge IP for IMDSv1.
