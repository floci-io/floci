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

## Instance Resource Limits

By default, Docker containers launched for EC2 instances are bounded to the CPU and memory limits defined in the instance type catalog (`src/main/resources/ec2/instance-type-catalog.yaml`).

- **CPU limit**: Each vCPU corresponds to 1024 CPU units (Docker nanoCPUs = `vcpu * 1_000_000_000`). If the host machine has fewer physical cores than requested, Floci clamps execution to available host CPUs and warns (since Docker refuses a nanoCPUs value exceeding the host CPU count).
- **Memory limit**: Container memory is bounded to the exact MiB value specified for the instance type in the catalog.
- **Types outside the full catalog**: Docker CPU and memory limits are not applied when the full catalog lacks a type. When opted in, Standard On-Demand admission requires a known default vCPU count, as described below.
- **Configuration knob**: To disable container resource limits (for example, running a large instance type on a developer machine with limited resources), set `floci.services.ec2.instance-resource-limits: false` (or environment variable `FLOCI_SERVICES_EC2_INSTANCE_RESOURCE_LIMITS=false`).

## Standard On-Demand admission

Quota admission is opt-in. Set `FLOCI_SERVICES_EC2_ENFORCE_VCPU_QUOTAS=true` to
check `RunInstances` and `StartInstances` against the applied Standard On-Demand
quota (`L-1216C47A`) for the caller's account and region. The flag defaults to
`false`, including mock mode. Disabled admission does not refuse a type merely
because its default vCPU count is absent from the snapshot.

`FLOCI_SERVICES_SERVICEQUOTAS_STANDARD_ON_DEMAND_VCPUS` supplies the applied value
(default `5`). Service Quotas reports this value independently of admission.
With admission enabled, a launch batch can return fewer than `MaxCount` when at
least `MinCount` fits, otherwise it returns `VcpuLimitExceeded`. A refused start
leaves stopped instances unchanged. Types in the Standard bucket require a
known default vCPU count; the separate CPU-only snapshot supplements the full
instance metadata catalog without inventing memory sizes or regional availability.

Only current `running` Standard On-Demand instances count. Pending, stopping,
stopped, shutting-down and terminated instances do not reserve capacity. Spot
and dedicated-host instances do not consume this bucket. RunInstances retains
direct or inherited launch-template Spot selection; explicit request market
options override the template. Requests for Spot through the other supported
launch APIs retain that provenance too.

These are checks of running usage at request admission, not a reservation ledger
or an eventual running ceiling. Accepted pending operations can complete after
other work consumes capacity, and changing a running instance's type is not
covered by the Run/Start checks. Admission does not change guest cleanup,
restart, user-data, public-address or CloudFormation lifecycle behavior.

## AMI to Docker Image Mapping

Floci resolves AMI IDs to Docker images from the EC2 image catalog at
`src/main/resources/ec2/image-catalog.yaml`. The same catalog stores the
fallback Docker image, per-AMI Docker image mappings, and `DescribeImages`
metadata.

| AMI ID | Aliases | Docker image |
|---|---|---|
| `ami-0abcdef1234567890` | `ami-amazonlinux2` | `public.ecr.aws/amazonlinux/amazonlinux:2` |
| `ami-0abcdef1234567891` | `ami-amazonlinux2023` | `public.ecr.aws/amazonlinux/amazonlinux:2023` |
| `ami-amazonlinux2023-arm64` | | `public.ecr.aws/amazonlinux/amazonlinux:2023` |
| `ami-0abcdef1234567892` | `ami-ubuntu2004` | `public.ecr.aws/docker/library/ubuntu:20.04` |
| `ami-ubuntu2204` | | `public.ecr.aws/docker/library/ubuntu:22.04` |
| `ami-ubuntu2404-arm64` | `ami-ubuntu2404` | `public.ecr.aws/docker/library/ubuntu:24.04` |
| `ami-ubuntu2404-amd64` | | `public.ecr.aws/docker/library/ubuntu:24.04` |
| `ami-ubuntu2404-cloud-arm64` | `ami-ubuntu2404-cloud` | `floci/ami-ubuntu:24.04-arm64` |
| `ami-debian12` | | `public.ecr.aws/docker/library/debian:12` |
| `ami-alpine` | | `public.ecr.aws/docker/library/alpine:latest` |
| `ami-0abcdef1234567893` | | `public.ecr.aws/amazonlinux/amazonlinux:2023` |
| `ami-opensuse-leap160` | | `registry.opensuse.org/opensuse/leap:16.0` |
| `ami-sles15sp4-byos` | | `registry.suse.com/bci/bci-base:15.4` |
| `ami-sles15sp5-byos` | | `registry.suse.com/bci/bci-base:15.5` |
| `ami-sles15sp6-byos` | | `registry.suse.com/bci/bci-base:15.6` |
| `ami-sles15sp7-byos` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-sles12sp5-byos` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-sles-sap15sp5` | | `registry.suse.com/bci/bci-base:15.5` |
| `ami-smlm-server51-x86-64` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-smlm-server51-arm64` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-smlm-proxy51-x86-64` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-smlm-proxy51-arm64` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-sles12sp5` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-sles15sp5` | | `registry.suse.com/bci/bci-base:15.5` |
| `ami-sles15sp6` | | `registry.suse.com/bci/bci-base:15.6` |
| `ami-sles15sp7` | | `registry.suse.com/bci/bci-base:15.7` |
| `ami-sles160` | | `registry.suse.com/bci/bci-base:16.0` |
| `ami-rocky9` | | `quay.io/rockylinux/rockylinux:9.6` |
| `ami-rocky10` | | `quay.io/rockylinux/rockylinux:10.0` |
| `ami-rhel8` | | `registry.access.redhat.com/ubi8/ubi:8.10` |
| `ami-rhel9` | | `registry.access.redhat.com/ubi9/ubi:9.5` |

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

If `KeyName` is specified at launch, Floci looks up the stored key pair's public key (generated by `CreateKeyPair` or supplied to `ImportKeyPair`) and copies it into `/root/.ssh/authorized_keys` inside the container at boot. It then attempts to start `sshd` if present. The SSH port (container port 22) is mapped to a host port from the configured range (default 2200–2299).

Key pairs created with `CreateKeyPair` return real RSA or ED25519 private key material (see [Key Pairs](#key-pairs)), and the matching public key is injected at launch, so either a created or an imported key pair works for SSH.

When the image lacks `sshd` or `scp`, Floci first installs OpenSSH with the image's package manager, from a shell that clears `LD_LIBRARY_PATH`, `PYTHONPATH` and `PYTHONHOME` as the IMDS proxy's helper shells do.

## Security Group Port Publishing

With `FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true`, Floci prepares a separate Linux network namespace and an nftables default-deny policy before starting each Docker-backed instance. It checks new managed connections against both the sender's egress rules and the receiver's ingress rules, using emulated ENI addresses and security-group membership before translating to Docker addresses. Established replies use connection tracking. This filters packets but does not emulate VPC routing, NACLs, NAT gateways, or peering. Rootless Docker and Windows containers are unsupported.

Security-group permissions on a host-published port use the source visible inside Docker. Docker Desktop may replace the original external-client address. The legacy socat application publisher is disabled while enforcement is enabled because it obscures that source. SSH is published directly by the protected namespace. Application host ports remain available through the legacy publisher only when enforcement is explicitly disabled. Direct managed container traffic uses the logical ENI identity.

When enforcement is disabled, an instance's security groups can open a TCP port to a CIDR source and Floci publishes that port on the host so you can reach the app from `localhost`. For each opened port Floci starts a small `alpine/socat` sidecar container that binds an allocated host port (default range 30000–30999) and forwards it to the instance container's IP, with `TCP_NODELAY` on both sockets so a request the client sends as two small writes is not held for the first write's delayed ACK (about 40 ms per round trip otherwise). This works both for rules present at launch and for rules added later with `authorize-security-group-ingress`; revoking the rule removes the forward. The mapping (`app port -> host port`) is written to the logs:

```
Published EC2 instance i-0abc... app port 80 on host port 30000 (socat -> 172.17.0.3:80)
```

Notes and limitations:

- The app inside the instance must listen on `0.0.0.0` (not `127.0.0.1`) for the forward to reach it.
- Only CIDR-sourced TCP rules are published. A port opened only to a referenced security group (or via a prefix list) is not published, matching AWS: those grant reachability from the referenced group's private IPs, not from the host. The source CIDR value itself is not enforced, so a CIDR-sourced port is reachable whether the rule is `0.0.0.0/0` or narrower.
- Ports are aggregated across all of the instance's security groups, SSH (22) is never re-forwarded, and any single rule whose port span exceeds `max-published-ports-per-instance` (default 20) is skipped so an allow-all range cannot spawn thousands of sidecars. The total published per instance is capped at the same limit.
- Stopping an instance tears down its forwards; starting it again does not automatically restore them (re-run `authorize-security-group-ingress`, or recreate the instance).
- With enforcement disabled, set `publish-security-group-ports: false` (`FLOCI_SERVICES_EC2_PUBLISH_SECURITY_GROUP_PORTS=false`) to keep security groups as metadata only.

## UserData

`UserData` must be base64-encoded in the request (matching the AWS wire format). Floci decodes it, copies the script into `/var/lib/user-data.sh` inside the container, and executes the script directly after SSH key injection so the script shebang selects the interpreter. Output is captured and logged.

EC2 containers receive `AWS_EC2_METADATA_SERVICE_ENDPOINT` for IMDS and `AWS_ENDPOINT_URL` for AWS service API calls back to Floci.

CloudFormation `AWS::EC2::VPCEndpoint` resolves `IpAddressType` on create and update, including parameter references. Omission, a selected `AWS::NoValue` branch, or `not-specified` uses the default `ipv4`; an explicitly empty value is rejected. Updates retain the existing replacement behavior; synthetic interfaces remain IPv4-only.

CloudFormation `AWS::EC2::LaunchTemplate` stores `LaunchTemplateData.MetadataOptions`, and `AWS::EC2::Instance` launches with its own `MetadataOptions` over those of the launch template it names, as `RunInstances` merges them, so CDK's `requireImdsv2` sets `HttpTokens=required`. A block or member of the wrong type, such as `HttpTokens: [required]`, fails the resource with CloudFormation's type error, a member given as an empty or padded string fails an instance with `InvalidParameterValue` (a launch template stores it unchecked, as `CreateLaunchTemplate` does, and the instances launched from it fail), and `AWS::NoValue` leaves a member or the whole block out. A stack update checks the options before it changes anything, applies changed ones in place, returns options it no longer declares to their defaults, and puts them back if the stack update rolls back. An update that kept the instance and changed no EC2 setting, such as one that only declares the options the instance already has, rolls back in full. When the same update also changed the instance's type, security groups, user data, profile or tags, which have no in-place rollback, the options still go back but the stack ends `UPDATE_ROLLBACK_FAILED` for the instance ("Rollback is not implemented"). The options handling includes an update that changes or drops the instance's `LaunchTemplate` (another template or version, as `Fn::GetAtt LatestVersionNumber` gives after the template changes): AWS replaces the instance then, as `LaunchTemplate` is a replacement property, but Floci keeps it and applies what the new template declares, unless the `ImageId` or `KeyName` the template supplies changed, which replaces it. A change set still previews a literal `LaunchTemplate` change as a replacement.

## Instance Metadata Service (IMDS)

Floci runs an IMDS-compatible HTTP server on port `9169` of the host. Each launched container receives the environment variable `AWS_EC2_METADATA_SERVICE_ENDPOINT` pointing to this server.

Inside each launched container, the link-local address `169.254.169.254` is a proxy to this
server. The helper shells that install and start the proxy clear `LD_LIBRARY_PATH`,
`PYTHONPATH` and `PYTHONHOME` before running system tools, so an application's library search
path and Python installation do not reach the package manager, `socat` and `curl`. The
container's configured environment and the application's processes keep these variables. The
proxy is ready once an IMDSv2 token request with a 60-second TTL succeeds and that token reads the
instance ID. The check bypasses any HTTP proxy the image configures, a failed or empty token
response does not fall back to a tokenless request, and the check passes on an instance that
requires tokens.

`iam/info` returns the ARN and ID that IAM holds for the attached profile, and 404 when that
profile is missing, has no role or does not match the attached ARN. An EKS cluster node, whose
profile Floci synthesizes, reports the synthesized ARN and a stable ID instead while IAM holds no
such profile.

Both IMDSv1 (no token) and IMDSv2 (token-based) flows are supported:

```bash
# IMDSv2 — get a token first
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
  -H "x-aws-ec2-metadata-token-ttl-seconds: 21600")

# Then use the token for metadata requests
curl -s -H "x-aws-ec2-metadata-token: $TOKEN" \
  http://169.254.169.254/latest/meta-data/instance-id
```

As on AWS, the token TTL must be an integer from 1 to 21600 seconds, or the `PUT` returns `400`. A metadata request that presents an unknown or expired token returns `401`, which tells the SDK to fetch a new token. A token is valid only on the instance that requested it: presenting it from another instance returns `401`. A request without a token header uses IMDSv1 unless the instance's `HttpTokens` is `required`; then, as on AWS, a `GET` or `HEAD` returns `401` and no metadata, for every path. `HttpTokens` comes from `RunInstances` (directly or through its launch template), from an EC2 Fleet's launch template, from Auto Scaling (a launch configuration's `MetadataOptions`, or a launch template given directly or through a mixed instances policy), or later from `ModifyInstanceMetadataOptions`. EKS cluster nodes always accept IMDSv1: `ModifyInstanceMetadataOptions` refuses them with `OperationNotPermitted`, as their stop, start, reboot and terminate actions are refused.

User data is returned as the original base64-decoded bytes, without decompressing gzip content or converting binary data to text. Older records without the original encoding, or records with malformed saved encoding, use their saved UTF-8 text. The response uses `application/octet-stream`, matching the [AWS user-data retrieval contract](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instancedata-data-retrieval.html). Missing or empty data returns `404`; whitespace is preserved. Script execution retains its separate bounded gzip decoding.

### Supported IMDS endpoints

| Endpoint | Returns |
|---|---|
| `GET /latest/meta-data/instance-id` | Instance ID |
| `GET /latest/meta-data/ami-id` | Image ID |
| `GET /latest/meta-data/instance-type` | Instance type |
| `GET /latest/meta-data/local-ipv4` | Private IP |
| `GET /latest/meta-data/public-ipv4` | Public IP — the container IP where that is routable from the host, `127.0.0.1` otherwise |
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
| `GET /latest/meta-data/identity-credentials/ec2/info` | Instance identity role info (`Code`, `LastUpdated`, `AccountId`) |
| `GET /latest/meta-data/identity-credentials/ec2/security-credentials/ec2-instance` | Instance identity role credentials |
| `GET /latest/user-data` | Original user-data bytes, including compressed content |
| `GET /latest/dynamic/instance-identity/document` | Identity document JSON |

IAM credentials are served when the instance has an `IamInstanceProfile.Arn` that resolves to an existing instance profile with one role in the profile's account. The role list uses the role name, which can differ from the profile name. Missing profiles, missing roles, and requests for another role return `404`.

Instance identity credentials are served for every instance, with or without an instance profile, under `identity-credentials/ec2/`, with directory listings at each level. They are a session of the instance identity role, `arn:<partition>:sts::<account>:assumed-role/aws:ec2-instance/<instance-id>` to `GetCallerIdentity`, where `<account>` is the account that launched the instance. As on AWS, no identity policy applies to that role, so with IAM enforcement on they are granted nothing beyond what needs no permission, such as `sts:GetCallerIdentity`. They follow the same lifetime, refresh and revocation rules as role credentials. The SSM Agent requests them first when it registers.

Each registered instance receives its own temporary IAM session. Credentials last one hour and refresh on retrieval during the final five minutes; the previous generation remains valid until expiration. Floci revokes tracked sessions when the instance is unregistered or IMDS shuts down, and discards persisted EC2 sessions when Floci restarts. Restored guests obtain fresh credentials after metadata registration is rebuilt.

Guests with an instance profile receive endpoint and region settings without Floci's static `test` credentials, allowing the standard SDK credential chain to reach IMDS. Guests without an instance profile retain the existing local `test` credential environment. Custom images can still override the SDK chain with their own credentials. This does not add EKS access-entry authorization or change Floci's global IAM enforcement configuration.

### IMDS and SSM managed instances

IMDS only knows about containers that Floci itself launched through `RunInstances`. That launch is what installs the link-local `169.254.169.254` proxy inside the container and maps the container's IP to its instance record.

Registering a container with SSM is independent of this: an SSM agent that calls `UpdateInstanceInformation` becomes a managed instance, but that does not create an EC2 instance record, does not install the IMDS proxy, and does not register the container with IMDS. From such a container, `curl http://169.254.169.254/...` fails outright (no proxy), and a request sent straight to the host IMDS port returns `404` with a message explaining that no EC2 instance is registered for the source IP. Floci also logs a warning when an SSM agent registers from a container that is not backed by a Floci EC2 instance.

To get a container that both answers IMDS (including instance profile credentials) and executes `SendCommand` directly, launch it with `RunInstances` first, then register that same container's SSM agent as a managed instance. Do not register an arbitrary or pre-existing container directly with SSM and expect IMDS to work. See [SSM](ssm.md#run-command-execution) for the SendCommand side of this.

### Cluster Node Instances

When an EKS cluster is running, its synthesized node instance is registered and visible through the EC2 service:

- **Visibility**: Exposed via `DescribeInstances` and `DescribeInstanceStatus` with its synthesized instance ID (`i-...`), type (`m5.large`), running state, VPC, subnet, launch time, and cluster tags (`Name`, `kubernetes.io/cluster/<cluster-name>=owned`, `eks:cluster-name=<cluster-name>`). It can be queried by ID, filtered, or listed with other instances.
- **Operations**: Supports volume attachments (`AttachVolume`, `DetachVolume`), instance attribute inspection and modification, and resource tagging (`CreateTags`, `DeleteTags`, `DescribeTags`).
- **Lifecycle protection**: Lifecycle actions that would mutate or delete the node through EC2 (`TerminateInstances`, `StopInstances`, `StartInstances`, `RebootInstances`), and `ModifyInstanceMetadataOptions`, which the node's IMDS would not see, are rejected with `OperationNotPermitted` (HTTP 400) as a Floci emulation limitation because the instance directly represents the underlying k3s cluster container. In real AWS, cluster node instances in an Auto Scaling group can be terminated or stopped and are subsequently replaced by the ASG. In Floci, the node instance lifecycle is managed solely through the EKS cluster lifecycle.

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

Where an action reads `Filter.N`, every filter is joined with an AND and the values of one filter
with an OR, as the [Filter](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_Filter.html)
reference describes. A name given twice counts as two filters, so `tag-key` with `Env` and
`tag-key` with `Team` find only the resources that carry both tag keys, and two `vpc-id` filters
naming different VPCs find nothing.

### Instances

| Action | Description |
|--------|-------------|
| RunInstances | Creates one or more local EC2 instances, starting Docker-backed runtime when not in mock mode. |
| CreateFleet | Creates an instant local fleet from launch-template configurations and on-demand or spot overrides. DryRun returns the AWS-compatible `DryRunOperation` error without launching instances. |
| DescribeInstances | Lists or returns stored EC2 instances. |
| TerminateInstances | Terminates instances and updates their stored lifecycle state. |
| StartInstances | Starts stopped instances and their local runtime when applicable. |
| StopInstances | Stops running instances and updates their stored lifecycle state. |
| RebootInstances | Reboots instances through the local EC2 service model. |
| DescribeInstanceStatus | Returns status records for stored instances. |
| DescribeInstanceCreditSpecifications | Returns the CPU credit option of burstable performance instances. A named instance id reports the option the instance acquired at launch, either the explicit `CreditSpecification.CpuCredits` or the family default, `standard` for t2 and `unlimited` for t3, t3a and t4g. An id that is not a burstable performance instance reports `standard`, and only an unknown id is an error. Naming no id returns the instances on the unlimited option, including one that kept `unlimited` after a resize onto a non-burstable type. `Filter.N` supports the modeled `instance-id` and narrows whichever set the request selected. `MaxResults` accepts 5 through 1000 and cannot be combined with instance ids. `DryRun=true` returns `DryRunOperation`. |
| DescribeInstanceAttribute | Returns a supported attribute for an instance. |
| ModifyInstanceAttribute | Updates supported mutable attributes for an instance. `UserData.Value` (base64) needs the instance stopped, as on AWS, and answers `IncorrectInstanceState` otherwise. |
| ModifyInstanceMetadataOptions | Updates an instance's IMDS options, changing only the fields the request names. An EKS cluster node is refused with `OperationNotPermitted`. |

### VPCs

| Action | Description |
|--------|-------------|
| CreateVpc | Creates a VPC with the requested CIDR block. `InstanceTenancy` accepts `default` or `dedicated`; `host` is rejected with `InvalidParameterValue`, as on AWS. |
| DescribeVpcs | Lists or returns stored VPCs. |
| DeleteVpc | Deletes a VPC from the local EC2 store, together with its default security group and rules, main route table and default network ACL. Fails with `DependencyViolation` while the VPC still has a subnet, a security group, route table or network ACL other than those defaults, a VPC endpoint, an attached internet gateway or egress-only internet gateway, a transit gateway attachment, an Elastic Load Balancing v2 load balancer, or anything still holding a network interface in one of its subnets: an instance that is not terminated, a NAT gateway that is not deleted or failed, a standalone network interface, or an interface endpoint. |
| ModifyVpcAttribute | Updates supported VPC attributes. |
| DescribeVpcAttribute | Returns a supported VPC attribute. |
| CreateDhcpOptions | Creates a DHCP options set with the supplied configurations and optional tags. |
| DescribeDhcpOptions | Lists DHCP options sets by ID or filters, with paginated results. |
| AssociateDhcpOptions | Associates an options set with a VPC; `default` leaves the VPC with no DHCP options. |
| DeleteDhcpOptions | Deletes an unassociated DHCP options set. |
| DescribeVpcEndpointServices | Lists common AWS interface endpoint services and S3 (gateway and interface) in every availability zone, named per partition (see [Partitions](../configuration/partitions.md)). A `ServiceName` filter is echoed back as asked. |
| CreateVpcEndpoint | Creates a VPC endpoint record, including its `PolicyDocument` and the per-subnet IPv4 and IPv6 addresses named by `SubnetConfiguration.N`. `IpAddressType` defaults to `ipv4`. Interface and gateway address types are stored as metadata. Synthesized endpoint interfaces still carry IPv4 only; IPv6 networking is not emulated. An `Ipv4` value outside the named subnet's CIDR, or among the first four or the last address AWS reserves in it, is rejected with `InvalidParameterValue`. |
| DescribeVpcEndpoints | Lists or returns stored VPC endpoints, including their IP address type. |
| ModifyVpcEndpoint | Associates or disassociates route tables, subnets and security groups, sets or resets the endpoint policy, and updates stored `IpAddressType` metadata. Omitting the type preserves its current value; malformed types are rejected before changes are applied. `SubnetConfiguration.N` replaces the addresses pinned for a subnet, under the same address validation as CreateVpcEndpoint. `DnsOptions` is accepted and ignored. |
| DeleteVpcEndpoints | Deletes VPC endpoint records. |
| DescribeVpnGateways | Validates filters and returns empty discovery results; explicit IDs return not-found errors. |
| CreateDefaultVpc | Creates or returns the default VPC for the region. |
| AssociateVpcCidrBlock | Adds a secondary CIDR block association to a VPC. |
| DisassociateVpcCidrBlock | Removes a secondary CIDR block association from a VPC. |

`DescribeVpnGateways` provides discovery compatibility when no resources exist; virtual
private gateway lifecycles are not modelled.

### Subnets

| Action | Description |
|--------|-------------|
| CreateSubnet | Creates a subnet in a VPC. |
| DescribeSubnets | Lists or returns stored subnets. |
| DeleteSubnet | Deletes a subnet from the local EC2 store. Fails with `DependencyViolation` while the subnet still has a transit gateway attachment, an Elastic Load Balancing v2 load balancer, or anything holding a network interface in it: an instance that is not terminated, a NAT gateway that is not deleted or failed, a standalone network interface, or an interface endpoint. |
| ModifySubnetAttribute | Updates supported subnet attributes. |

### Security Groups

| Action | Description |
|--------|-------------|
| CreateSecurityGroup | Creates a security group in a VPC. |
| DescribeSecurityGroups | Lists or returns stored security groups. |
| DeleteSecurityGroup | Deletes a security group from the local EC2 store. |
| AuthorizeSecurityGroupIngress | Adds inbound permissions. Sources may be IPv4 ranges, IPv6 ranges, or another security group (`UserIdGroupPairs`, sent on the wire as `Groups`); prefix list sources are not stored. One rule is stored per source, each carrying its own description. |
| AuthorizeSecurityGroupEgress | Adds outbound permissions, with the same source types as the inbound call. |
| RevokeSecurityGroupIngress | Removes inbound permissions. Matches on protocol and port range only, so it removes every permission on that port regardless of source. |
| RevokeSecurityGroupEgress | Removes outbound permissions, matched the same way as the inbound call. |
| GetSecurityGroupsForVpc | Lists the security groups belonging to one VPC, with the same filters as the describe call. |
| DescribeSecurityGroupRules | Lists stored security group rules. |
| ModifySecurityGroupRules | Updates supported fields on security group rules. |
| UpdateSecurityGroupRuleDescriptionsIngress | Updates descriptions on matching inbound security group rules. |
| UpdateSecurityGroupRuleDescriptionsEgress | Updates descriptions on matching outbound security group rules. |

### Key Pairs

`ImportKeyPair` takes the key types and formats AWS documents: an RSA or ED25519 key as an OpenSSH
public key line, and an RSA key also as an RFC 4716 file or as base64 DER, bare or in PEM armour.
AWS lists 1024, 2048 and 4096 bits as the supported RSA lengths but imports others too, such as
8192 bits; Floci takes any length from 1024 to 16384 bits, the range OpenSSH reads, which also
covers ssh-keygen's default of 3072. A DSA, ECDSA, security-key or certificate key, a key line
whose blob is incomplete, has bytes after its key or declares another type, and material holding
more than one key line are refused with `InvalidKey.Format`. The key is stored as one OpenSSH line,
which is what `RunInstances` copies into `authorized_keys`. `CreateKeyPair` and `ImportKeyPair`
take a `KeyName` of up to 255 ASCII characters with no leading or trailing space; a missing or
blank one is `MissingParameter`, and they answer any other with `InvalidParameterValue`. Either
action refuses a name that a key pair in the account and region already has with
`InvalidKeyPair.Duplicate`, and of two that ask for a free name at the same moment, one stores its
key pair and the other gets that error.

`DescribeKeyPairs` returns each key pair's `keyType` and `createTime`. It filters by `key-pair-id`,
`key-name`, `fingerprint`, `tag-key` and `tag:<key>`, as AWS documents, and also by `key-type`,
which AWS does not document; `*` and `?` are wildcards. Filters are ANDed, a repeated name
included, and the values of one filter are ORed. Any other filter name is `InvalidParameterValue`.
It omits the public key unless `IncludePublicKey=true`, read as `true` or `false` in any case and
as `false` when empty or blank; any other value is `InvalidParameterValue`. When requested, it
returns the OpenSSH public key with the key-pair name as its comment, whatever comment the key was
imported with; a name holding a control character, such as a line break, is left off. It never
returns private key material.

A key pair saved by an earlier version of Floci is described from what it stored. It has no
`createTime`, and its `keyType` is read from the stored public key, created or imported, and is
`rsa` for a created key pair saved before Floci kept its public key; it is left out when imported
material would not import now or cannot be read. Its imported material is kept as it was given,
and is what `RunInstances` copies into `authorized_keys`. Material that is not exactly one whole OpenSSH public key is described without
one: PEM, DER or RFC 4716 text, material of more than one line, a line whose blob lacks a field its
type requires (such as an `ssh-rsa` blob with no modulus), has bytes after its key or declares a
different type than the line names, and a key of any type but `ssh-rsa`, `ssh-ed25519`,
`ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521`, `sk-ssh-ed25519@openssh.com`
and `sk-ecdsa-sha2-nistp256@openssh.com`.

| Action | Description |
|--------|-------------|
| CreateKeyPair | Creates an RSA or ED25519 key pair and returns its private key as PEM or as a PuTTY `.ppk` file, with `key-pair` tags from `TagSpecification` returned in `tagSet`. |
| DescribeKeyPairs | Lists or returns stored key pairs. |
| DeleteKeyPair | Deletes a key pair from the local EC2 store. |
| ImportKeyPair | Imports a public key as a local key pair, with `key-pair` tags from `TagSpecification` returned in `tagSet`. |

`CreateKeyPair` creates a 2048-bit RSA key unless `KeyType=ed25519` asks for an ED25519 key, and
returns its private key as PEM unless `KeyFormat=ppk` asks for a PuTTY key file, in the forms AWS
returns: the PKCS#1 `BEGIN RSA PRIVATE KEY` block for an RSA key, OpenSSH's own
`BEGIN OPENSSH PRIVATE KEY` format for an ED25519 key, and for `ppk` PuTTY's version 2 format,
unencrypted, with the key pair's name as its comment. The fingerprint follows the key's type rather
than its format: the SHA-1 of the DER private key for RSA, as colon-separated hex, and the base64
SHA-256 of the public key for ED25519, with the `=` padding that `ssh-keygen -l` leaves off. Any other
`KeyType` or `KeyFormat` is `InvalidParameterValue`, and nothing is created. `DryRun=true` is answered
with `DryRunOperation` before any of these checks, and creates nothing either.

### AMIs

| Action | Description |
|--------|-------------|
| DescribeImages | Returns AMI metadata known to the local EC2 service. |
| CreateImage | Captures an instance as a new AMI. Reboots the source unless `NoReboot=true`. Applies `image` and `snapshot` tags from `TagSpecification` to the AMI and its new snapshots. |
| RegisterImage | Registers an AMI from supplied metadata and block device mappings, with `image` tags from `TagSpecification`. |
| CopyImage | Copies an AMI from `SourceRegion` into the request's region as a new AMI the caller owns, applying `image` and `snapshot` tags from `TagSpecification` to the copy and its new snapshots. |
| DeregisterImage | Deregisters an AMI. Its snapshots are kept unless `DeleteAssociatedSnapshots=true`, and a snapshot another AMI still uses is kept either way. |

Every resource EC2 creates is owned by the account the request resolves to, the same account
[STS](sts.md) reports for those credentials, and that account is what `ownerId` and the resource ARN
carry. So `DescribeImages` with `--owners <your account id>` matches the AMIs that account
registered, and `--owners self` resolves to the same account. This is what lets a Terraform
`aws_ami` data source pin `owners` to the account under test instead of the emulator's default
`000000000000`. The `amazon` and `aws-marketplace` aliases still resolve to the AWS-owned accounts
that publish those images.

`DescribeImages` reports where an AMI came from. An AMI made by `CreateImage` reports the instance
it captured as `SourceInstanceId`, and the AMI that instance was launched from, with its region, as
`SourceImageId` and `SourceImageRegion`. An AMI made by `CopyImage` reports the AMI it copied and
that AMI's region, and no instance. Each names its immediate source by AMI ID, even when the
instance was launched, or the copy requested, with a catalog alias such as `ami-amazonlinux2`, and
keeps naming it after the source is deregistered. AMIs made by `RegisterImage`, catalog AMIs and
AMIs saved by an earlier Floci version report none. The `source-image-id`, `source-image-region` and
`source-instance-id` filters match these fields, and `source-image-id` also takes a catalog alias,
as `image-id` does.

### Tags

| Action | Description |
|--------|-------------|
| CreateTags | Adds tags to supported EC2 resources. |
| DeleteTags | Removes tags from supported EC2 resources. |
| DescribeTags | Lists tags stored for EC2 resources. |

Tags are regional, like the resources they label: `DescribeTags` lists only the tags of resources
in the request's region, and a tag set in one region is not visible from another. Each result
carries the resource's `resourceType` (`image`, `volume`, `snapshot`, `network-acl`,
`vpc-peering-connection` and `spot-instances-request` included). Tags given when a volume or a
VPC peering connection is created are listed like any other tag, and `DescribeVolumes`,
`DescribeSnapshots` and `DescribeSpotInstanceRequests` show later `CreateTags` and `DeleteTags`
changes. Each side of a VPC peering connection sees the tags set in its own account and region.
A resource's tags go with it: deleting a volume, a snapshot or a peering connection, or the
removal of a terminated instance, removes its tags. On upgrade, tags stored by an older Floci
version are moved under their resource's region at startup. Tags on a catalog AMI move to the
default region, and tags on an EKS node instance move to the node's region once the node is
restored; any other tag whose resource no longer exists is dropped.

`CreateTags` and `DeleteTags` require at least one resource id (`MissingParameter`) and reject an
id that is not a known EC2 resource prefix followed by 8 or more hex digits or, for fleets, a UUID
(`InvalidID`). `CreateTags` also
checks each resource exists in the request's region and answers with that type's own error
(`InvalidVpcID.NotFound`, `InvalidInstanceID.NotFound`, ...); a well-formed id of a type Floci does
not model is tagged as given. A resource takes at most 50 user tags (`TagLimitExceeded`; tags with
the `aws:` prefix do not count), keys up to 128 and values up to 256 Unicode characters
(`InvalidParameterValue`). A request that fails changes no resource's tags.

`DescribeTags` takes the `key`, `value`, `resource-id`, `resource-type` and `tag:<key>` filters.
`tag:Owner` with the value `TeamA` is the same as a `key` filter of `Owner` and a `value` filter
of `TeamA`, so it lists that one tag. Filter values take the `*` and `?` wildcards, the values of
one filter are ORed, and separate filters, including two with the same name, are ANDed. An
unknown filter name is ignored. `MaxResults` (5 to 1000, `InvalidMaxResults` otherwise) pages the
result with `NextToken`, which is absent on the last page; the AWS SDK paginators work as is.
Results are ordered by resource id, so pages stay stable while no tags change.

Public tag mutations reject keys beginning with `aws:` regardless of ASCII case.
An empty `DeleteTags` tag list removes user tags and preserves AWS-managed tags.
CloudFormation tag updates and rollbacks likewise leave AWS-managed tags intact.
Instance and security-group templates validate desired tags before changing resource properties
or deleting old tags. Volume and Spot request creation also reject reserved keys before allocation.
Nested launch-template tags follow AWS's case-sensitive check: `aws:` is rejected with
`InvalidTagKey.Malformed`, while `AWS:` and mixed-case prefixes can be stored in template versions.

### Internet Gateways

| Action | Description |
|--------|-------------|
| CreateInternetGateway | Creates an internet gateway. |
| DescribeInternetGateways | Lists or returns stored internet gateways. |
| DeleteInternetGateway | Deletes an internet gateway. |
| AttachInternetGateway | Attaches an internet gateway to a VPC. |
| DetachInternetGateway | Detaches an internet gateway from a VPC. |
| CreateEgressOnlyInternetGateway | Creates an egress-only internet gateway attached to an existing VPC (`InvalidVpcID.NotFound` otherwise). Tags are validated before the gateway is created, so a rejected tag leaves nothing behind. A retry with the same `ClientToken` returns the gateway the first call created, unchanged; the same token with a different `VpcId` or different `TagSpecification` tags fails with `IdempotentParameterMismatch`. |
| DescribeEgressOnlyInternetGateways | Lists stored egress-only internet gateways, filtered by ID and by the `egress-only-internet-gateway-id`, `attachment.vpc-id`, `attachment.state` and tag filters. An unknown ID returns an empty set, as AWS does. `MaxResults` (5 to 255) pages the results, ordered by ID, with `NextToken`. |
| DeleteEgressOnlyInternetGateway | Deletes an egress-only internet gateway; an unknown ID fails with `InvalidGatewayID.NotFound`. Routes that targeted it stay in their route tables with state `blackhole`. |

### Route Tables

| Action | Description |
|--------|-------------|
| CreateRouteTable | Creates a route table in a VPC. |
| DescribeRouteTables | Lists or returns stored route tables. |
| DeleteRouteTable | Deletes a route table from the local EC2 store. |
| AssociateRouteTable | Associates a route table with a subnet. |
| DisassociateRouteTable | Removes a route table association. |
| CreateRoute | Adds a route to a route table. Accepts `InstanceId`, `NetworkInterfaceId`, and `VpcPeeringConnectionId` as targets (alongside `GatewayId`/`NatGatewayId`/`EgressOnlyInternetGatewayId`) and reports them back on `DescribeRouteTables`. |
| ReplaceRoute | Replaces the target of an existing route, accepting `InstanceId`, `NetworkInterfaceId`, and `VpcPeeringConnectionId`. |
| DeleteRoute | Removes a route from a route table. Refuses the route to the VPC CIDR that `CreateRouteTable` seeds with `InvalidParameterValue`, and reports a destination with no route as `InvalidRoute.NotFound`. |

### VPC Peering Connections

| Action | Description |
|--------|-------------|
| CreateVpcPeeringConnection | Creates a peering connection between the local VPC and a peer VPC, in status `pending-acceptance`. |
| AcceptVpcPeeringConnection | Transitions a `pending-acceptance` connection to `active`. |
| DescribeVpcPeeringConnections | Lists or returns stored peering connections. |
| ModifyVpcPeeringConnectionOptions | Sets `allow_remote_vpc_dns_resolution` independently per side. |
| DeleteVpcPeeringConnection | Deletes a peering connection from the local EC2 store. |

Real AWS never auto-accepts a connection, same-account or not: every `CreateVpcPeeringConnection`
starts `pending-acceptance` and stays there until an explicit `AcceptVpcPeeringConnection`. The
`auto_accept` convenience on Terraform's `aws_vpc_peering_connection` and
`aws_vpc_peering_connection_accepter` resources is implemented by the *provider*, which simply
issues that second call itself — so this emulator does not special-case same-account peers.

A connection is stored keyed by its id alone, not `region::id` like every other EC2 resource here.
It is meaningfully addressable from both the requester's and the accepter's side, which can be a
different region (`peer_region`/`accepter_region`); region-scoped storage would leave the accepter's
`AcceptVpcPeeringConnection`/`DescribeVpcPeeringConnections` calls unable to find a connection
created under the requester's region key. `RejectVpcPeeringConnection` is not implemented — no
Gruntwork VPC-peering example exercises it (they use `auto_accept`, not manual rejection).

The accepter VPC named by `PeerVpcId` may belong to another account or region and not be modelled
in this store at all (a cross-account or "external" peer). Its `cidrBlock` is reported only when
that VPC happens to exist locally; the request still succeeds either way, and no CIDR is fabricated.

A connection's storage entry lives under whichever account's request created it, but lookups
(`AcceptVpcPeeringConnection`, `DescribeVpcPeeringConnections`, `ModifyVpcPeeringConnectionOptions`,
`DeleteVpcPeeringConnection`) resolve it across every account's partition, the same pattern used for
RAM-shared IPAM resources. `AcceptVpcPeeringConnection` additionally enforces that the caller is the
connection's accepter — reporting a connection it cannot see as absent, not as a permission error,
matching how AWS itself responds.

### Network ACLs

| Action | Description |
|--------|-------------|
| CreateNetworkAcl | Creates a network ACL in a VPC, with `network-acl` tags from `TagSpecification`. |
| DescribeNetworkAcls | Lists or returns stored network ACLs. |
| DeleteNetworkAcl | Deletes a network ACL from the local EC2 store. |
| CreateNetworkAclEntry | Adds an entry to a network ACL. The entry keeps whichever of `CidrBlock` or `Ipv6CidrBlock` was supplied, and `DescribeNetworkAcls` returns it under the same name. `IcmpTypeCode` is kept for ICMP (`1`) and ICMPv6 (`58`) entries only. |
| ReplaceNetworkAclEntry | Replaces an entry in a network ACL, including its `CidrBlock` or `Ipv6CidrBlock` and, for ICMP and ICMPv6 entries, its `IcmpTypeCode`. |
| DeleteNetworkAclEntry | Removes an entry from a network ACL. |
| ReplaceNetworkAclAssociation | Replaces the network ACL associated with a subnet. |

### Prefix Lists

| Action | Description |
|--------|-------------|
| DescribePrefixLists | Returns prefix lists known to the local EC2 service. |
| CreateManagedPrefixList | Creates a customer-managed prefix list with its initial entries. |
| DescribeManagedPrefixLists | Lists customer-managed and AWS-managed prefix lists. |
| GetManagedPrefixListEntries | Returns the entries of a prefix list, optionally at an earlier version. |
| ModifyManagedPrefixList | Adds or removes entries, renames the list, or raises its entry limit. |
| DeleteManagedPrefixList | Deletes a customer-managed prefix list. |

Two AWS-managed prefix lists exist in every region without being created —
`com.amazonaws.<region>.s3` (`pl-63a5400a`) and `com.amazonaws.<region>.dynamodb`
(`pl-02cd2c6b`) — matching the gateway endpoint services on AWS. They are owned by `AWS`,
read-only, and served by both `DescribePrefixLists` and `DescribeManagedPrefixLists`;
modifying or deleting one returns `UnsupportedOperation`.

Additional regional AWS-managed lists can be supplied through
`FLOCI_SERVICES_EC2_MANAGED_PREFIX_LIST_CATALOG_PATH`. Set it to a readable JSON
file inside the Floci container, for example:

```json
{
  "eu-west-1": [{
    "prefixListId": "pl-0123456789abcdef0",
    "prefixListName": "example-managed-service",
    "cidrs": ["192.0.2.0/24"]
  }]
}
```

The file is read once when EC2 starts. Each entry needs a unique ID and name
within its region and at least one IPv4 CIDR. CIDRs are normalized to network
blocks; repeated or equivalent blocks within one list are rejected. Entries
cannot replace the built-in lists. Invalid or unreadable files prevent startup
when EC2 is enabled; disabled EC2 does not read the catalog. Configured lists
have the same AWS ownership, read-only behavior, security-group reference
validation and describe/entry responses as the built-in lists. Both configured
and built-in CIDRs participate in security-group policy evaluation and firewall
rules when enforcement is enabled. They are available across accounts in their
configured region. Leaving the setting unset keeps the built-in catalog.

A customer-managed list may not take a name AWS reserves for its own: `com.amazonaws.`,
`com.amazon.` or `com.aws.`, each including the trailing dot. `CreateManagedPrefixList`
rejects those with `InvalidParameterValue`; a name that merely resembles one, such as
`com.amazonaws-internal`, is allowed.

Entries are versioned. A prefix list starts at version 1, and each `ModifyManagedPrefixList`
that adds or removes entries stores a new version and bumps the counter, so
`GetManagedPrefixListEntries` can serve an earlier `TargetVersion`. Renaming the list or
changing `MaxEntries` does not create a version. Passing `CurrentVersion` makes the
modification conditional: a stale value returns `PrefixListVersionMismatch`. Removals are applied before
additions, so one call can replace an entry's description by removing and re-adding the CIDR.

Creation is synchronous: a new list is returned as `create-complete` rather than passing
through `create-in-progress`, since nothing about it is slow locally.

A security group rule can take a prefix list as its source instead of a CIDR. Pass it as
`IpPermissions.N.PrefixListIds.M.PrefixListId`, optionally with a `Description`; AWS emits one rule
per source, so a permission naming both CIDRs and prefix lists expands to a rule for each. The
resulting rule carries `prefixListId` in place of `cidrIpv4`, and `DescribeSecurityGroups` nests
the reference under the permission as `prefixListIds`. Authorizing against a list that does not
exist returns `InvalidPrefixListID.NotFound`, so a typo cannot leave a rule pointing at nothing.

### Transit Gateways

| Action | Description |
|--------|-------------|
| CreateTransitGateway | Creates a transit gateway, applying AWS's option defaults and minting its default route table. |
| DescribeTransitGateways | Lists or returns stored transit gateways. |
| ModifyTransitGateway | Updates a transit gateway's description, options and CIDR blocks. |
| DeleteTransitGateway | Deletes a transit gateway and the default route table created with it. |

Transit gateway metadata only: nothing routes packets, and the value is in ids that later
resources can reference and describes that round-trip so plans converge.

Options left out of `CreateTransitGateway` take the same defaults AWS applies — `amazonSideAsn`
64512, `dnsSupport`, `vpnEcmpSupport`, `defaultRouteTableAssociation` and
`defaultRouteTablePropagation` enabled, and `autoAcceptSharedAttachments`,
`securityGroupReferencingSupport` and `multicastSupport` disabled. `transitGatewayCidrBlocks` is
omitted from the response entirely when no blocks are set, rather than sent empty.

Creating a gateway with either default-route-table option enabled also creates the route table
AWS creates, and reports its id as `associationDefaultRouteTableId` and
`propagationDefaultRouteTableId`. Both name the same table. Disabling both leaves the ids absent.
The actions that operate on transit gateway route tables directly — creating them, associating
attachments, enabling propagation — are not implemented yet, and neither are attachments.

State is reported settled rather than transitional: AWS returns a new gateway as `pending` and
reaches `available` roughly a minute later, and reports `deleting` before `deleted`. Nothing here
is slow, so callers see `available` and `deleted` immediately. `ModifyTransitGateway` and
`DeleteTransitGateway` echo the gateway without its `tagSet`, matching AWS; `CreateTransitGateway`
and `DescribeTransitGateways` include it.

### Transit Gateway VPC Attachments

| Action | Description |
|--------|-------------|
| CreateTransitGatewayVpcAttachment | Attaches a VPC to a transit gateway through one subnet per availability zone. |
| DescribeTransitGatewayVpcAttachments | Lists or returns VPC attachments with their subnets and options. |
| DescribeTransitGatewayAttachments | Returns the same attachments in the resource-agnostic shape, including the route table association. |
| DescribeTransitGatewayConnects | Always returns an empty list, since Connect attachments cannot be created yet; requested ids are still validated. |
| ModifyTransitGatewayVpcAttachment | Adds or removes attachment subnets and updates its options. |
| DeleteTransitGatewayVpcAttachment | Deletes a VPC attachment. |

An attachment's option defaults are its own rather than the gateway's: `dnsSupport` and
`securityGroupReferencingSupport` enabled, `ipv6Support` and `applianceModeSupport` disabled. Note
that `securityGroupReferencingSupport` is enabled here while a transit gateway defaults it to
disabled.

The attachment is associated with the gateway's default route table only when the gateway carries
`defaultRouteTableAssociation` enabled; a gateway created without it produces an attachment with no
association. That association is reported by `DescribeTransitGatewayAttachments` alone — the
VPC-specific describe does not carry it, and the resource-agnostic one carries neither the subnets
nor the options in exchange.

Subnets must belong to the VPC being attached and no two may share an availability zone; one from
another VPC is reported as `InvalidSubnetID.NotFound` rather than as a mismatch. A VPC can be
attached to a given gateway once, so a second attempt returns `DuplicateTransitGatewayAttachment`.
Removing every subnet returns `InsufficientSubnetsException`, and a gateway with a live attachment
cannot be deleted — `IncorrectState`, naming the attachments.

As with the gateway itself, state is reported settled rather than transitional, and the echoes are
trimmed the way AWS trims them: modify omits the `tagSet`, and delete omits both the `tagSet` and
the subnets. `Ipv6Support` is accepted without checking that the subnets carry IPv6 CIDRs, which
real AWS rejects; Floci does not model subnet IPv6 allocation.

### Transit Gateway Peering Attachments

| Action | Description |
|--------|-------------|
| CreateTransitGatewayPeeringAttachment | Requests a peering between a local transit gateway and a peer one, pending acceptance. |
| DescribeTransitGatewayPeeringAttachments | Lists or returns peering attachments, from either side's region. |
| AcceptTransitGatewayPeeringAttachment | Accepts a pending peering attachment from the accepter's region. |
| DeleteTransitGatewayPeeringAttachment | Deletes a peering attachment from either side. |

The requester's gateway must exist; the peer gateway is recorded as given and not resolved, since
it may belong to an account this emulator cannot see. One attachment id serves both sides, so a
cross-region peering is visible from both regions, and the `transit-gateway-id` filter matches
either side's gateway. Creation reports `pendingAcceptance` (AWS passes through
`initiatingRequest` first), accepting moves it to `available`, and accepting anything but a pending
attachment returns `IncorrectState`. Across accounts, only the peer account can see the attachment
from its side and accept it, and accepting checks that the peer gateway exists in the accepter's
account and region, returning `InvalidTransitGatewayID.NotFound` otherwise. Each account keeps its
own tags on the attachment. A pending peering does not stop the peer gateway's owner deleting it;
that rejects the peering instead. Peering attachments do not yet appear in
`DescribeTransitGatewayAttachments` and cannot be associated with a route table.

### Transit Gateway Route Tables

| Action | Description |
|--------|-------------|
| CreateTransitGatewayRouteTable | Creates a route table on a transit gateway. |
| DescribeTransitGatewayRouteTables | Lists or returns stored transit gateway route tables. |
| DeleteTransitGatewayRouteTable | Deletes a route table, along with its propagations and static routes. |
| AssociateTransitGatewayRouteTable | Associates an attachment with a route table. |
| DisassociateTransitGatewayRouteTable | Removes an attachment's association. |
| GetTransitGatewayRouteTableAssociations | Lists the attachments associated with a route table. |
| EnableTransitGatewayRouteTablePropagation | Propagates an attachment's routes into a route table. |
| DisableTransitGatewayRouteTablePropagation | Stops an attachment propagating into a route table. |
| GetTransitGatewayRouteTablePropagations | Lists the propagations into a route table. |
| CreateTransitGatewayRoute | Adds a static or blackhole route to a route table. |
| DeleteTransitGatewayRoute | Removes a static route. |
| ReplaceTransitGatewayRoute | Points an existing route at a different target, or writes it if absent. |
| SearchTransitGatewayRoutes | Returns a route table's routes, static and propagated, filtered. |
| ExportTransitGatewayRoutes | Reports the S3 object a route-table export would be written to. |

A route table asked for by name is never a default one; only the table a gateway mints for itself
carries `defaultAssociationRouteTable` or `defaultPropagationRouteTable`. Deleting a route table is
refused with `IncorrectState` while it is a gateway's default association table, and again while
attachments are still associated with it; the two cases carry different messages. Once it does go,
its propagations and static routes go with it.

An attachment is associated with exactly one route table at a time, so associating a second time
returns `Resource.AlreadyAssociated` rather than moving it — disassociate first. Association is
recorded on the attachment itself, which is why `GetTransitGatewayRouteTableAssociations` reports
the attachment's VPC as the associated resource.

Propagation is separate: one attachment may propagate into several route tables. Enabling twice
returns `TransitGatewayRouteTablePropagation.Duplicate`. Unlike association, which reports
`associating` and `disassociating`, propagation reports the settled `enabled` or `disabled` at
once — that is what the live API does rather than a shortcut taken here.

`ReplaceTransitGatewayRoute` is an upsert rather than an update: replacing a destination the table
has never held writes it instead of reporting it missing, which is what the live API does. The
target moves as a unit, so a route turned into a blackhole keeps no attachment and one pointed back
at an attachment regains all of its fields.

`SearchTransitGatewayRoutes` serves both kinds of route. Static routes are stored as written; a
blackhole is a static route in the `blackhole` state rather than a type of its own, and carries no
attachment. Propagated routes are derived when searched, from each enabled propagation joined to
the attached VPC's CIDR blocks, so a VPC's CIDRs changing cannot leave a stale route behind. A
route table's own listings drop the route table id that the mutating calls include, matching AWS.

Route table ids follow the live API's own inconsistency: an id that does not exist is
`InvalidRouteTableID.NotFound`, while one of the wrong shape is `InvalidRouteTableId.Malformed`.

`ExportTransitGatewayRoutes` validates the route table and requires `S3Bucket`, then returns the `s3://` object key the export would occupy. No object is written and nothing is uploaded — the value is a caller that needs the call to succeed and the key to look right, not a readable export.

### NAT Gateways

| Action | Description |
|--------|-------------|
| CreateNatGateway | Creates a NAT gateway record. |
| DescribeNatGateways | Lists or returns stored NAT gateways. |
| DeleteNatGateway | Deletes a NAT gateway record. |

### Capacity Reservations

| Action | Description |
|--------|-------------|
| CreateCapacityReservation | Reserves EC2 instance capacity in a specific Availability Zone. |
| DescribeCapacityReservations | Lists or returns stored Capacity Reservations. |
| ModifyCapacityReservation | Updates `InstanceCount`, `EndDate`, `EndDateType` or `InstanceMatchCriteria` in place. |
| CancelCapacityReservation | Marks a Capacity Reservation `cancelled` and its available count `0`, matching real AWS's retain-but-cancel behaviour rather than deleting the record. |

`InstanceType`, `InstancePlatform` and `InstanceCount` are required, matching the AWS API, and
one of `AvailabilityZone` or `AvailabilityZoneId` must be given; a request missing any of
these is rejected with `MissingParameter`. `InstanceCount` must be greater than `0` on create
and on modify, otherwise `InvalidParameterValue`. `InstanceMatchCriteria` defaults to `open`,
`Tenancy` defaults to `default` and `EndDateType` defaults to `unlimited`. Creation is
synchronous: the reservation comes back `active` on the create response rather than passing
through `payment-pending`/`assessing`. `DescribeCapacityReservations` supports the
`availability-zone`, `end-date-type`, `instance-match-criteria`, `instance-platform`,
`instance-type`, `state` and `tenancy` filters alongside the shared `tag:`, `tag-key` and
`tag-value` filters.

On-demand launches consume matching open reservation capacity unless their preference is
`none`. RunInstances also honors an explicit reservation target, including one supplied by
its launch template. `capacity-reservations-only` requires matching reservation capacity and
never falls back to On-Demand capacity when launching or restarting. It can accompany an
explicit reservation target; without one, only matching open reservations are eligible.
A matching targeted reservation with too few available slots returns
`ReservationCapacityExceeded` on launch or restart.
CreateFleet and AutoScaling retain the template's preference and target;
CloudFormation launch templates preserve the same declaration. Creating an open reservation
adopts matching, unassociated running instances with an `open` preference. DescribeInstances
reports the acquired reservation. Stop, termination,
cancellation and expiration release membership; a restart acquires capacity using the
instance's retained launch preference and target. A failed membership write reports an error;
subsequent access reconciles released membership from persisted instance state.

Membership and available counts publish in one atomic storage batch. Older snapshots without
membership remain readable.

`CapacityReservationResourceGroupArn` targets the members of an existing capacity pool
in the caller's account and region. Only members with matching instance type, platform,
Availability Zone and tenancy are eligible. Both open and targeted member reservations
can supply capacity. The default permits On-Demand fallback when the group has no
matching available capacity; `capacity-reservations-only` forbids that fallback at launch
and restart. A group target cannot accompany a reservation ID, `none`, or a Spot request.
DescribeInstances retains the group target separately from the acquired reservation ID.
Running group-targeted instances can acquire newly added matching members or move to
another matching member after cancellation or expiration. Existing membership survives
group deletion until the instance releases its reservation.


Capacity reservation zone IDs must be among those returned by `DescribeAvailabilityZones`.

### Dedicated Hosts

| Action | Description |
|--------|-------------|
| AllocateHosts | Allocates `Quantity` (default `1`) Dedicated Hosts, `available` immediately, with `dedicated-host` tags from `TagSpecification`. |
| DescribeHosts | Lists or returns stored hosts, including the instances placed on each. |
| ModifyHosts | Updates `AutoPlacement`, `HostRecovery`, `HostMaintenance`, `InstanceType` or `InstanceFamily`. |
| ReleaseHosts | Marks hosts `released`; they stay visible in `DescribeHosts`, as on AWS. |

`AvailabilityZone` and exactly one of `InstanceType` or `InstanceFamily` are required.
`AutoPlacement` and `HostRecovery` default to `off` and `HostMaintenance` to `on`.
`DescribeHosts` with an unknown id fails with `InvalidHostID.NotFound`. `ModifyHosts` and
`ReleaseHosts` report each host separately: an unknown or already released host, or a host
still carrying instances on release, goes to `unsuccessful` instead of failing the call.
`RunInstances` accepts `Placement.HostId` and `Placement.Tenancy`; naming a host defaults the
tenancy to `host` and, without a subnet, the zone to the host's. `hostProperties` reports a
fixed socket/core/vCPU shape rather than one derived from the instance family.

### Elastic IPs

| Action | Description |
|--------|-------------|
| AllocateAddress | Allocates an Elastic IP address record. |
| DescribeAddresses | Lists or returns stored Elastic IP address records. |
| DescribeAddressesAttribute | Returns allocation ID and public IP attributes for Elastic IP addresses. |
| AssociateAddress | Associates an Elastic IP address with a resource. |
| DisassociateAddress | Removes an Elastic IP address association. |
| ReleaseAddress | Releases an Elastic IP address record. |

`AllocateAddress` and `DescribeAddresses` return `PublicIpv4Pool` and `NetworkBorderGroup`.
Omitted values default to `amazon` and the request Region, including for older persisted
allocations without these fields. Explicit values survive association, disassociation and
restart. `DescribeAddresses` also supports the `network-border-group` filter. These values
are stored as metadata; Floci does not allocate real BYOIP addresses or route by border group.
See [AllocateAddress](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_AllocateAddress.html)
and [Elastic IP allocation](https://docs.aws.amazon.com/vpc/latest/userguide/WorkWithEIPs.html).

`ReleaseAddress` automatically disassociates an instance's Elastic IP only in the default VPC.
In a nondefault VPC it returns `InvalidIPAddress.InUse` without changing the association;
call `DisassociateAddress` first, as specified by the
[EC2 API reference](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_ReleaseAddress.html).
Associating an Elastic IP with an instance that already has one on the same private address
disassociates the earlier address, which stays allocated, as the
[AssociateAddress reference](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_AssociateAddress.html)
describes. That is the primary private address unless `PrivateIpAddress` names a secondary
address of the instance's primary interface, so an address on a secondary private address
leaves the primary one, and the instance's public address, in place. For an existing instance,
any other `PrivateIpAddress` fails with `InvalidParameterValue` and changes no association. The
exception is container mode without AWS-faithful private addresses: the instance reports its Docker
bridge address there, so any value means its primary address.

Deleting an interface, including deletion on instance termination, disassociates its
Elastic IP without releasing the allocation. A retained interface keeps its association
after termination; release still follows that interface's VPC rules.

AllocateAddress draws Elastic IPs from `203.0.113.0/24`, a range reserved for documentation that
no host answers on, in every mode, so a client never reaches someone else's host through one. An
allocation never takes an address the account's other Elastic IPs or instances already hold; once
all 254 are taken, AllocateAddress fails with `AddressLimitExceeded`. Elastic IPs allocated by an
earlier Floci version keep their `54.x.x.x` address until released. Associating an Elastic IP with an
instance that has a container re-points it at the address the instance is actually reachable on
(the same value DescribeInstances reports), and so does associating one with a mock instance's
primary private address under `FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESSES=fixed`, where it becomes
the configured address. This is a deliberate deviation from AWS, where an EIP's public IP is fixed
from allocation: without it, `aws_eip.x.public_ip` hands every caller a dead address. The
allocation ID, association ID and domain are unaffected, and disassociating restores the
allocated address.

### Availability Zones & Regions

| Action | Description |
|--------|-------------|
| DescribeAvailabilityZones | Returns the configured local availability zones. |
| DescribeRegions | Returns the regions known to the local EC2 service. |
| DescribeAccountAttributes | Returns local account-level EC2 attributes. |

The modeled zones are standard Availability Zones. Each reports `NetworkBorderGroup` as its
Region, such as `eu-west-1`. Local Zones and Wavelength Zones are not modeled.

A zone id has AWS's shape, a short region prefix and `-az<n>`: `use1-az1` in `us-east-1`, `apne1-az1`
in `ap-northeast-1`, `usgw1-az1` in `us-gov-west-1`. On AWS the pairing of a zone name with a zone id
differs per account; Floci always pairs zone `a` with `az1`, `b` with `az2`, and so on. Subnets, VPC
flow-log records and EFS mount targets report the same id for the same zone. The ISO and EUSC
regions, for which AWS publishes no zone id, keep the whole region as the prefix
(`us-iso-east-1-az1`).

### Instance Types

| Action | Description |
|--------|-------------|
| DescribeInstanceTypes | Returns instance type metadata known to the local EC2 service, including `burstablePerformanceSupported` for the T families. |
| DescribeInstanceTypeOfferings | Returns instance type offerings for the requested location filters. An `instance-type` filter value may hold `*` and `?` wildcards, such as `t3.*`. |

### Launch Templates

| Action | Description |
|--------|-------------|
| CreateLaunchTemplate | Creates a launch template with an initial version. |
| CreateLaunchTemplateVersion | Creates a new launch template version, optionally from a source version. |
| DescribeLaunchTemplates | Lists or returns stored launch templates. |
| DescribeLaunchTemplateVersions | Lists versions stored for a launch template. |
| ModifyLaunchTemplate | Updates launch template metadata such as the default version. |
| DeleteLaunchTemplate | Deletes a launch template and its versions. |

Launch templates store versioned launch data. New template versions can be created from an existing source version, and `ModifyLaunchTemplate` updates the default version used by later launches.

#### Launch template data members

These members of `RequestLaunchTemplateData` are stored and read back unchanged by
`DescribeLaunchTemplateVersions`:

`ImageId`, `InstanceType`, `KeyName`, `UserData`, `KernelId`, `RamDiskId`, `SecurityGroupIds`,
`IamInstanceProfile`, `BlockDeviceMappings`, `NetworkInterfaces`, `TagSpecifications`,
`MetadataOptions`, `Monitoring`, `Placement`, `CpuOptions`, `CreditSpecification`,
`EnclaveOptions`, `HibernationOptions`, `MaintenanceOptions`, `PrivateDnsNameOptions`,
`CapacityReservationSpecification`, `InstanceMarketOptions`, `InstanceRequirements`,
`EbsOptimized`, `DisableApiTermination`, `DisableApiStop`,
`InstanceInitiatedShutdownBehavior`.

`InstanceRequirements` carries every member of the service model's
`InstanceRequirementsRequest`, including the nested `BaselinePerformanceFactors.Cpu.References`.
Within `NetworkInterfaces`, `ConnectionTrackingSpecification` is stored and read back with all
three of its timeout members.

At launch, `RunInstances` and `CreateFleet` apply the template's `BlockDeviceMappings` under the
request's, field by field: a request entry for a `DeviceName` the template maps, spelled `xvdf` or
`/dev/xvdf`, changes only the members it sets, and the rest keep the template's values; devices
only one side maps apply as given. A request entry that makes the device something else, an EBS
volume (`Ebs`), an instance store volume (`VirtualName`) or nothing (`NoDevice`), replaces the
template's choice for it, an `Ebs` block still merging member by member into the template's. An
Auto Scaling group launching from a template gets its mappings the same way; one launching from a
launch configuration does not pass the configuration's mappings yet. CloudFormation does not pass
them yet: an `AWS::EC2::Instance` launches without the `BlockDeviceMappings` it or its launch
template declares, and an `AWS::EC2::LaunchTemplate` or
`AWS::AutoScaling::LaunchConfiguration` stores none, so instances launched from what a stack
creates get the AMI's devices alone. A mapping naming a `VolumeType` outside the service model's
enum fails with `InvalidParameterValue`, as `ModifyVolume` does; one with `NoDevice` set creates no
device, so its `VolumeType` is not checked. Template versions are not checked when they are stored,
so a template naming an unknown `VolumeType` is accepted by `CreateLaunchTemplate` and fails at
launch.

The launch's mappings then apply over the AMI's own block device mapping the same way, and the
launch creates one EBS volume for each device left, attached at that device:

- The instance's root device is the AMI's `RootDeviceName`, which the AMI's entry for it may spell
  `xvda` or `/dev/xvda`. An AMI that maps no root device gets an 8 GiB root volume. A launch entry
  for a device the AMI maps applies to it whether it is spelled `xvdf` or `/dev/xvdf`. `NoDevice`
  keeps any other device the AMI maps from being created, and an entry naming a `VirtualName`, an
  instance store volume, takes the device over, so neither leaves an EBS volume there. The root
  is always created: an entry setting either on it leaves the AMI's root as the AMI maps it. An
  AMI entry with no `Ebs` member, such as an instance store volume, creates nothing.
- The catalog's images are rooted at `/dev/xvda`, including some AWS publishes with a
  `/dev/sda1` root, such as Ubuntu and Windows, so on them an entry for `/dev/sda1` adds a data
  volume rather than resizing the root.
- A volume from a snapshot takes the snapshot's size unless its entry names a larger one. A smaller
  size fails with `InvalidBlockDeviceMapping` ("Volume of size 8GB is smaller than snapshot
  'snap-...', expect size>= 16GB"), and a snapshot that does not exist fails with
  `InvalidSnapshot.NotFound`. An entry naming neither a size nor a snapshot gets 8 GiB. Snapshots
  hold no data in Floci, so a volume made from one starts empty.
- The root takes the AMI root's `VolumeType`, else `gp3`; other devices default to `gp2`, as
  `CreateVolume` does. `Iops` applies only to `gp3`, `io1` and `io2` volumes and `Throughput` only
  to `gp3`, so a request entry that changes a template device's type does not carry the old type's
  settings over. A volume is encrypted when its entry or its snapshot says so, or when encryption
  by default is on.
- `DeleteOnTermination` defaults to `true` for every device a launch creates. When the instance
  terminates, the volumes whose
  attachment says `true` are deleted, the root included, unless another instance still has them
  attached; the rest are detached. That holds as well for an instance that ends `terminated`
  because its container never started: its volumes are settled the next time an EBS volume call
  reads them, `DescribeVolumes` or a call naming one of them. A root kept this way holds no data
  when the instance had a container, whose own file system was the root.
- An entry without a `DeviceName` fails with `InvalidBlockDeviceMapping` ("Missing device name"),
  as do a device name `AttachVolume` would refuse ("Invalid device name ...") and a device the
  request or the template names twice ("/dev/sdh is being assigned to multiple times."), whether or
  not a launch template is used. `CreateFleet` checks the device names before answering `DryRun`.
- Nothing of a refused launch is kept: no instance, volume or network interface. A launch undone
  after its instances exist, an instant `CreateFleet` whose later launch fails or a `RunInstances`
  whose capacity reservation another launch took in the meantime, deletes every volume it created,
  those with `DeleteOnTermination` false included. Auto Scaling and CloudFormation undo instances
  they launched by terminating them, so there the volumes termination keeps stay.

`DescribeInstances` lists every EBS volume attached to the instance in `BlockDeviceMappings`, root
device first: those its launch created and any attached since, each with its own
`DeleteOnTermination`; a terminated instance lists none.

Both blocks are validated by `CreateLaunchTemplate` and `CreateLaunchTemplateVersion` before
anything is stored. An `InstanceRequirements` block must carry `VCpuCount` and `MemoryMiB`, each
with its `Min`, which the service model declares required; a request missing either fails with
`MissingParameter`. A connection tracking timeout outside the range its member documents fails
with `InvalidParameterValue`: `TcpEstablishedTimeout` runs from 60 to 432000 seconds,
`UdpTimeout` from 30 to 60, and `UdpStreamTimeout` from 60 to 180.

Three combinations the service model documents in prose rather than in its constraints are
rejected with `InvalidParameterCombination`:

- `InstanceRequirements` and `InstanceType` together. A launch template selects instance types by
  attribute or by name, not by both.
- `AllowedInstanceTypes` and `ExcludedInstanceTypes` together inside `InstanceRequirements`.
- `SpotMaxPricePercentageOverLowestPrice` and `MaxSpotPriceAsPercentageOfOptimalOnDemandPrice`
  together inside `InstanceRequirements`.

`CreateLaunchTemplateVersion` applies these three to the merged version as well as to the request
it received, because the merged data is what the version stores. A version that names only
`InstanceType` against a source version carrying `InstanceRequirements` is therefore rejected: the
merge inherits the requirements block and cannot express its removal. Omitting `SourceVersion`
starts from empty data and is how a template moves between the two selection modes.

Two behaviours worth calling out, because they are what Terraform reads back:

- **`IamInstanceProfile` keeps the form it was given.** A profile submitted as `Name` reads back as
  `Name`, not rewritten to `Arn`. At launch time, Floci resolves that name against IAM in the
  caller's account and preserves the profile's full path in its ARN. A name missing from that
  account is rejected with `InvalidParameterValue`. This also applies to direct `RunInstances`
  requests and `CreateFleet` launches, so `aws_launch_template.iam_instance_profile.name` converges.
- **`NetworkInterfaces` stays a `NetworkInterfaces` block.** Its `Groups` are not hoisted into
  top-level `SecurityGroupIds`; on AWS the two are mutually exclusive. A launch from the template
  resolves its security groups from whichever of the two is populated. `RunInstances` and
  `CreateFleet` launches take their subnet and `AssociatePublicIpAddress` from the interface at
  device index 0; when that interface names no subnet, `CreateFleet` still uses the first
  interface that does. A `RunInstances` launch whose template interface at device index 0 names
  an existing `NetworkInterfaceId` launches on that interface, in its subnet, as a request
  naming it would. Auto Scaling launches take only its `AssociatePublicIpAddress`, since the
  group's `VPCZoneIdentifier` places them. Network interfaces, on the `RunInstances` request or
  in its launch template, cannot be combined with an instance-level `SubnetId`
  (`InvalidParameterCombination`); place the subnet on the primary network interface instead.

#### Launch template versions

For a specified template, `DescribeLaunchTemplateVersions` returns all stored versions in
ascending numeric order by default. `LaunchTemplateVersion.N` selects explicit version numbers,
`$Latest`, or `$Default`; the legacy `Versions.N` spelling is also accepted. Without an explicit
version list, `MinVersion` is exclusive and `MaxVersion` inclusive. Explicit versions take
precedence over the range. Filtering, pagination, and listing across all templates are unchanged.

`CreateLaunchTemplateVersion` accepts two members outside `LaunchTemplateData` itself:

- **`SourceVersion` controls inheritance, and omitting it means no inheritance.** A request that
  names a source version — including `$Latest` or `$Default` — layers its own fields onto that
  version's data, so only the fields it restates change. A request that omits `SourceVersion`
  entirely does **not** fall back to the latest version: the new version starts from an empty
  `LaunchTemplateData`, populated only by whatever fields the request itself supplies. This matches
  the AWS-documented behavior; it does not merge onto any prior version.
- **`VersionDescription` is stored and read back by `DescribeLaunchTemplateVersions`.** It is a
  version-level field on `LaunchTemplateVersion`, not a member of `RequestLaunchTemplateData` /
  `ResponseLaunchTemplateData`, so it is tracked per version alongside `VersionNumber` and
  `CreateTime` rather than inside the launch template data payload. `CreateLaunchTemplate` accepts
  the same field for the initial version it creates.

Members the service model declares that are accepted and ignored rather than stored:
`LicenseSpecifications`, `ElasticGpuSpecifications`, `ElasticInferenceAccelerators`,
`NetworkPerformanceOptions`, `Operator`, `SecondaryInterfaces` and
`SecurityGroups` (security groups by name, where resolving names to IDs would need lookup machinery,
including ambiguity handling across VPCs, that no other EC2 action here has either; `RunInstances`
itself only accepts `SecurityGroupId`). Within `NetworkInterfaces`, the IPv4/IPv6 address and
prefix lists, `EnaSrdSpecification`, `PrimaryIpv6` and `EnaQueueCount` are likewise ignored.

### IAM Instance Profiles

| Action | Description |
|--------|-------------|
| DescribeIamInstanceProfileAssociations | Lists IAM instance profile associations known to the local EC2 service. |
| AssociateIamInstanceProfile | Attaches a profile, named by `IamInstanceProfile.Arn` or `IamInstanceProfile.Name`, to an instance that has none; a second association answers `IncorrectState`. |
| ReplaceIamInstanceProfileAssociation | Swaps the profile behind an existing association id; an unknown id answers `InvalidAssociationID.NotFound`. |
| DisassociateIamInstanceProfile | Detaches the profile behind an association id. |

Launches and association changes resolve both names and ARNs against IAM in the
caller's account. Unknown profiles and foreign-account ARNs fail with
`InvalidParameterValue` before allocation or association changes. A RunInstances launch
that names both a profile name and an ARN, directly or through its launch template, and a
CreateFleet launch through such a template, fail with `InvalidParameterCombination`;
association replacement uses the ARN when both are provided. Launch templates reject
malformed profile ARNs at creation with `InvalidIamInstanceProfileArn.Malformed` but, as
on AWS, store a name and ARN pair as given; the pair fails when something uses the
template, and Auto Scaling refuses a group over it.
EC2 stores and returns IAM's profile ID, including for launch-template, EC2 Fleet and
Auto Scaling instances. Persisted instances from older versions omit the unknown ID until the
profile is reassociated; looking up the ARN could misidentify a deleted and recreated
profile.

### Network Interfaces

| Action | Description |
|--------|-------------|
| CreateNetworkInterface | Creates a standalone elastic network interface (ENI) in a subnet, unattached. |
| DescribeNetworkInterfaces | Lists network interfaces known to the local EC2 service, both an instance's implicit primary interface and standalone ENIs created via `CreateNetworkInterface`. |
| AttachNetworkInterface | Attaches an available standalone ENI to a running or stopped instance at a device index. |
| DetachNetworkInterface | Detaches a standalone ENI by attachment ID, returning it to `available`. |
| DeleteNetworkInterface | Deletes a standalone ENI. Fails while the ENI is still attached, matching AWS. |

A standalone ENI created via `CreateNetworkInterface` can also be handed to `RunInstances` as an instance's primary interface (`NetworkInterface.1.NetworkInterfaceId` / `NetworkInterface.1.DeviceIndex`) instead of letting the instance create its own implicit one, the pattern Terraform's `aws_instance` resource uses for `network_interface { network_interface_id = ... }`. AWS only allows this for a single instance per launch call; `RunInstances` rejects it otherwise with `InvalidParameterCombination`.

`ModifyNetworkInterfaceAttribute` is not implemented: no example in the corpus that needed `CreateNetworkInterface` was found to need it. A route table's `CreateRoute` with a `NetworkInterfaceId` target is accepted but not recorded, since `Route` does not yet model an ENI target; a subsequent `plan` against such a route may show drift.

### Volumes

| Action | Description |
|--------|-------------|
| CreateVolume | Creates an EBS volume record. |
| DescribeVolumes | Lists or returns stored EBS volume records. |
| DeleteVolume | Deletes an EBS volume record. |
| ModifyVolume | Modifies size, type, IOPS, throughput, or multi-attach settings of an EBS volume record. |
| DescribeVolumesModifications | Reports the current modification state for EBS volumes, filterable by volume ID and state. |
| AttachVolume | Attaches a volume to an instance at the requested device; returns the attachment in `attaching` state. |
| DetachVolume | Detaches a volume from an instance, optionally forced; returns the attachment in `detaching` state. |

When `floci.services.ec2.volume-block-devices` is enabled (the default), attached EBS volumes are backed by real Linux loop devices inside running instance containers and synthesized EKS cluster node containers:
- **Storage mechanism**: Backing sparse raw image files are allocated in Floci storage under `floci-aws-ec2-volumes` (named volume or host path). A privileged helper container (`floci-aws-ec2-volume-helper`, running `floci.services.ec2.volume-helper-image`, default `alpine:3.21`) manages Linux loop devices via `losetup`. It starts on the first volume operation and is removed when Floci shuts down; the backing files stay in storage.
- **Target device nodes**: Inside privileged target instance containers, the corresponding device node is created at the requested path (for example `/dev/xvdf` or `/dev/sdf`) using `mknod` or a symlink to the loop device, matching the exact size specified during volume creation. Target containers can format filesystems (such as ext4 or xfs), mount them, and persist data across detach and reattach.
- **Restart reconciliation**: When an instance container stops and starts, or when Floci restarts, attached volume device nodes are automatically restored inside the target container. Backing raw files remain preserved across reboots in persistent storage.
- **Graceful degradation**: If Docker is unavailable, the target instance container is not running, or the container is not privileged, volume attachments degrade gracefully to metadata-only tracking without failing the API call.
- **Platform requirements**: Requires a Linux Docker environment (native Linux Docker daemon, or Colima / Docker Desktop with a Linux virtual machine) and privileged instance containers.
- **Unmodeled aspects**: Multi-attach is tracked at metadata level only. Automated filesystem formatting (volumes start unformatted like real EBS block devices) and volume encryption at the block layer are not modeled. Root volume backing is logical (container rootfs); `ModifyVolume` updates recorded root volume metadata without modifying container storage, while EBS data volume size increases expand the underlying raw backing file and refresh any attached loop device. Volume type, IOPS, and throughput changes remain recorded metadata without altering device characteristics.

Validation matches AWS behavior:
- Unknown volumes are rejected with `InvalidVolume.NotFound`.
- Decreasing volume size is rejected with `InvalidParameterValue`.
- Unsupported parameters (such as `Throughput` on non-gp3 volumes or `Iops` on non-provisioned IOPS volume types) are rejected with `InvalidParameterCombination`.
- When querying `DescribeVolumesModifications` with an explicit volume ID for an unmodified volume, the request fails with `InvalidVolumeModification.NotFound`. Listing or filtering without explicit IDs returns only modified volumes.

### Snapshots

| Action | Description |
|--------|-------------|
| DescribeSnapshots | Lists or returns stored snapshots, filterable by id and owner. |

### Flow Logs

| Action | Description |
|--------|-------------|
| CreateFlowLogs | Creates flow logs for the given resources; returns one flow log id per resource. |
| DescribeFlowLogs | Lists stored flow logs, optionally filtered by id. |
| DeleteFlowLogs | Deletes the named flow logs. |

### Spot Instances

| Action | Description |
|--------|-------------|
| RequestSpotInstances | Requests spot instances from a launch specification; returns the created spot instance requests. |
| DescribeSpotInstanceRequests | Lists spot instance requests, optionally filtered by id. |
| CancelSpotInstanceRequests | Cancels the named spot instance requests, returning each id with its new state. |
| DescribeSpotPriceHistory | Returns synthetic spot price history for known instance types across modeled availability zones. |

`RequestSpotInstances` preserves the original base64 user data in the Spot request
and launched instance. Binary and gzip payloads remain intact when described or
retrieved through IMDS; script execution uses the same bounded decoder as
`RunInstances`. Invalid base64, oversized payloads and malformed gzip return
`InvalidParameterValue` before launch. Older stored Spot requests without the
original encoding return base64 derived from their saved UTF-8 text.

Spot prices returned by `DescribeSpotPriceHistory` are synthetic and derived deterministically from instance type specifications: `((vcpu * 0.0016) + (memoryGiB * 0.0012)) * (1.0 + (azOffset * 0.03))`, formatted to six decimal places. They bear no relation to real AWS spot prices and are intended for testing cost-aware schedulers rather than spend estimation. Supported filters are `availability-zone`, `availability-zone-id`, `instance-type`, `product-description`, `spot-price`, and `timestamp`. Pagination is supported via `MaxResults` and `NextToken`.

### VPN Gateways

| Action | Description |
|--------|-------------|
| DescribeVpnGateways | Returns an empty gateway set (no stored gateways). |

### EBS Encryption Defaults

| Action | Description |
|--------|-------------|
| EnableEbsEncryptionByDefault | Turns on default encryption for new volumes in the region. |
| DisableEbsEncryptionByDefault | Turns default encryption back off. |
| GetEbsEncryptionByDefault | Reports whether default encryption is on. |
| ModifyEbsDefaultKmsKeyId | Sets the KMS key used when a volume names none. |
| GetEbsDefaultKmsKeyId | Reports the current default KMS key. |
| ResetEbsDefaultKmsKeyId | Restores the AWS-managed default key. |

These are account-level settings scoped per region, not per volume, and nothing here encrypts anything — no volume's stored bytes change. LZA's SecurityStack drives them through its `Custom::EnableEbsEncryptionByDefault` Lambda, which calls enable plus `ModifyEbsDefaultKmsKeyId` on create and disable on delete, then reads the state back with the two `Get` calls.

An account that has never set a key reports `alias/aws/ebs`, the AWS-managed EBS key every account starts with, rather than an empty value — the module runner fails hard on a missing `KmsKeyId`, so the fallback is what keeps it running. `ResetEbsDefaultKmsKeyId` returns to that same alias. `ModifyEbsDefaultKmsKeyId` requires `KmsKeyId` and rejects a blank one with `MissingParameter`; the key is stored as given and is not checked against KMS.

### Snapshot Block Public Access

| Action | Description |
|--------|-------------|
| EnableSnapshotBlockPublicAccess | Sets the region's snapshot sharing block to `block-all-sharing` or `block-new-sharing`. |
| DisableSnapshotBlockPublicAccess | Returns the region to `unblocked`. |
| GetSnapshotBlockPublicAccessState | Reads the region's current state. |

This is an account-level setting scoped per region, not a resource, so there is no id and nothing to tag. A region that was never configured reads back `unblocked`. `EnableSnapshotBlockPublicAccess` accepts only `block-all-sharing` and `block-new-sharing`, and rejects `unblocked` with `InvalidParameterValue` the way AWS does: disabling goes through `DisableSnapshotBlockPublicAccess`, which returns the resulting `unblocked` rather than the prior state. A missing `State` is rejected with `MissingParameter`.

Only `GetSnapshotBlockPublicAccessState` returns `managedBy`, and it always reports `account` because Floci has no declarative-policy layer that could take the setting over. Nothing here changes snapshot permissions: no snapshot's `createVolumePermission` is rewritten when the block goes on or off.

All three actions honor `DryRun`. A request that would otherwise succeed returns `DryRunOperation` with HTTP 412 and leaves the stored state untouched. `EnableSnapshotBlockPublicAccess` validates `State` first, so an invalid or missing `State` is still rejected on its own error even when `DryRun=true` is set.

### IPAM

| Action | Description |
|--------|-------------|
| EnableIpamOrganizationAdminAccount | Delegates IPAM administration to a member account. |
| DisableIpamOrganizationAdminAccount | Removes the IPAM delegated administrator. |
| CreateIpam | Creates an IPAM with its default private and public scopes. |
| DescribeIpams | Lists or returns stored IPAMs. |
| ModifyIpam | Updates an IPAM's description, tier, metered account and operating regions. |
| DeleteIpam | Deletes an IPAM and, leniently, the pools that belong to it. |
| CreateIpamPool | Creates a pool under a scope, optionally sourced from a parent pool. |
| DescribeIpamPools | Lists or returns stored pools. |
| ModifyIpamPool | Updates a pool's description, auto-import flag and netmask-length bounds. |
| DeleteIpamPool | Deletes a pool. |
| ProvisionIpamPoolCidr | Provisions a CIDR onto a pool, validated against its source pool. |
| GetIpamPoolCidrs | Returns a pool's provisioned CIDRs. |
| AllocateIpamPoolCidr | Allocates a CIDR from a pool, by explicit CIDR or by netmask length. |
| ReleaseIpamPoolAllocation | Releases an allocation, returning its space to the pool. |
| GetIpamPoolAllocations | Returns a pool's live allocations. |
| AssociateIpamByoasn | Associates a BYOASN with a CIDR. |
| DisassociateIpamByoasn | Removes a BYOASN association. |
| DescribeIpamByoasn | Lists BYOASN associations in the region. |

This is what LZA needs end to end: the Organization stage delegates the IPAM admin through `Custom::EnableIpamOrganizationAdminAccount`, the Network stages build the IPAM and pool hierarchy through CloudFormation, and the `get-ipam-subnet-cidr` custom-resource Lambda allocates subnet CIDRs from pools at Deploy time.

Allocation is real rather than recorded. `AllocateIpamPoolCidr` by netmask length hands out the first free block that fits, skipping both live allocations and any space already provisioned onward to child pools, and reports `InsufficientCidrBlocks` when nothing fits. An explicit `Cidr` must fall inside a provisioned CIDR and must not overlap an existing allocation. `ProvisionIpamPoolCidr` on a pool with a source pool requires the CIDR to sit inside one of the parent's provisioned CIDRs. Releasing an allocation returns its space, so the next allocation of the same size reuses it.

`CreateIpam`, `CreateIpamPool`, `ProvisionIpamPoolCidr` and `AllocateIpamPoolCidr` honour `ClientToken` — the four IPAM operations that model it. A replay returns what the first call produced rather than creating a second resource: the same pool, the same provisioned CIDR, the same allocation id and CIDR, with pool consumption unchanged. This matters most on `AllocateIpamPoolCidr`, which LZA's `get-ipam-subnet-cidr` Lambda retries; without it each retry would burn another distinct CIDR out of the pool. Parameter differences on a replay are ignored rather than rejected as `IdempotentParameterMismatch`, and the token is not echoed in the response, matching the AWS output shapes. A token is scoped to the account that used it.

Pool lookups deliberately fall back to an id-only scan across accounts, so a RAM-shared pool resolves from a workload account and region. That fallback covers reads and allocation only: `AllocateIpamPoolCidr` from an account that does not own the pool succeeds and writes the allocation back to the owner's partition rather than forking a copy into the caller's. Mutations of the pool itself — `ModifyIpamPool`, `DeleteIpamPool`, `ProvisionIpamPoolCidr` — are owner-only, as are `ModifyIpam` and `DeleteIpam`, and a non-owner gets `InvalidIpamPoolId.NotFound` or `InvalidIpamId.NotFound`, which is what AWS returns for a resource you cannot act on. `ReleaseIpamPoolAllocation` stays on the cross-account path, since no per-allocation caller is tracked to check ownership against.

The delegated administrator is stored organization-wide rather than per account, so every member account reads the same value and delegating a second, different account conflicts with `InvalidParameterValue` no matter which account asks.

Omitting a required identifier is a modeled `MissingParameter` rather than a not-found: this covers `IpamPoolId` on every pool operation, and `IpamScopeId` on `CreateIpamPool`. A `CreateIpamPool` naming a scope no IPAM owns is rejected with `InvalidIpamScopeId.NotFound` instead of storing a pool with a null `ipamId`.

State is reported settled rather than transitional, as elsewhere in this service: IPAMs and pools come back `create-complete` immediately and `delete-complete` on deletion, with no intermediate states. `DeleteIpam` cascades to the IPAM's pools, which real AWS requires `--cascade` to do. BYOASN associations are stored and echoed but nothing validates the ASN or advertises it.

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
| `FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESSES` | `none` | Automatic public IPv4 address of a mock instance: `none`, `documentation` (from `203.0.113.0/24`, which routes nowhere) or `fixed` (see below). Instances with containers always report their reachable address (see [Public IPv4 addresses](#public-ipv4-addresses)) |
| `FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESS` | `127.0.0.1` | The address every mock instance due one gets when `FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESSES` is `fixed`, for example this host's own address; an Elastic IP associated with its primary private address shows it too |
| `FLOCI_SERVICES_EC2_AWS_FAITHFUL_PRIVATE_IP` | `false` | Report the CFN/subnet-allocated private IP instead of the container bridge IP; routing and IMDS are unaffected |
| `FLOCI_SERVICES_EC2_CONTAINER_IPS_ROUTABLE` | auto-detect | Whether an instance's container IP is reachable from the machines consuming Floci's API (Terraform, Terratest, your shell). When it is, DescribeInstances and DescribeAddresses report the container IP, so port 22 really is port 22; when it is not, they report `127.0.0.1` and reachability goes through the published high host ports. Detected by a throwaway TCP connect; set explicitly when Floci itself runs as a container |
| `FLOCI_SERVICES_EC2_RECONCILE_CONTAINERS_ON_STARTUP` | `true` | On startup, remove instance containers this Floci left on the daemon whose record did not survive the restart or came back terminated; stopped instances are never swept |
| `FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED` | `true` | Back each VPC with a real Docker network (see [VPC Docker networks](#vpc-docker-networks)) |
| `FLOCI_SERVICES_EC2_VPC_NETWORKS_FALLBACK_POOL` | `10.240.0.0/12` | RFC 1918 pool that substituted CIDRs are drawn from |
| `FLOCI_SERVICES_EC2_VPC_NETWORKS_FALLBACK_PREFIX_LENGTH` | `16` | Prefix length of each block handed out of that pool |
| `FLOCI_SERVICES_EC2_VPC_NETWORKS_RECONCILE_ON_STARTUP` | `true` | Remove VPC networks left behind by a previous run of this same emulator |
| `FLOCI_SERVICES_EC2_VPC_NETWORKS_DRIVER` | `bridge` | Docker network driver used for VPC networks |

## VPC Docker networks

Each VPC is backed by a real Docker network, created lazily when the first instance in that VPC
launches. An instance's reported private IP is then an address its container actually holds, drawn
from the subnet CIDR the caller declared, not a plausible-looking number. Instances in the same
VPC reach each other at those addresses; instances in different VPCs sit on different bridges.

`CreateVpc` and `CreateSubnet` record declarations without contacting Docker. CIDR collision
checks and fallback address planning run when a consumer first needs an effective address or
network attachment. This keeps management calls responsive even when the Docker daemon is slow.
For overlapping VPC CIDRs, the first VPC whose addresses are needed keeps the declared range;
later VPCs use the fallback pool. Within one emulator run, allocated addresses remain on the
same effective range.

When Floci runs in Docker, deleting a VPC disconnects Floci's own container before removing
the VPC network. Other endpoints are not forcibly disconnected: removal retries while they
drain, up to five attempts two seconds apart. After successful removal, a new VPC can reuse
the CIDR without colliding with a network left behind by that deletion.

When EC2 is enabled, Floci initializes its service, restores persisted state, and seeds the
configured region's default resources during startup. Other account and region scopes seed on
first access. Docker reconciliation can lengthen startup, and Docker-backed instance launches
still depend on daemon responsiveness.

One network per **VPC**, not per subnet: subnets inside a VPC route to each other in AWS, so a
network per subnet would manufacture a partition AWS does not have. Per-subnet addressing is kept
anyway: the network's IPAM pool is the whole VPC CIDR and each subnet allocates static addresses
out of its own slice of it.

An address a subnet picks on its own is never one that an instance, network interface or NAT
gateway in the subnet already holds. Default subnet ids are the same in every account, so those
subnets share a network, and what any account holds there counts.

The declared CIDR is used verbatim whenever it can be. It cannot be when it is absent, malformed,
outside RFC 1918, or already claimed on the Docker daemon, including by another Floci VPC, since
two VPCs may legally declare the same CIDR in AWS but one daemon cannot route two identical
ranges. Only then is an equivalent block taken from `fallback-pool`, and the substitution is
**logged at WARN**: a reported private IP that does not mean what the caller declared is either
true or it is in the log.

Limits worth knowing before reading a passing test as evidence:

- **Security groups and NACLs are not enforced by this.** Within one Docker network every
  container reaches every other on every port.
- **Between-VPC isolation is the daemon's, not Floci's.** It comes from Docker's own
  `DOCKER-ISOLATION-STAGE` rules. OrbStack does not apply them: measured on OrbStack 29.4.0,
  two containers on separate networks reach each other in both directions, `--internal` included.
  On such a host the VPC boundary is an addressing boundary only.
- **On Docker Desktop for macOS and Windows container addresses do not answer from the host.**
  The address is real and reachable container-to-container, but a host-side client cannot dial
  it. This is why SSH keeps its published host port.

Set `FLOCI_SERVICES_EC2_VPC_NETWORKS_ENABLED=false` to go back to synthesised private addresses
and the shared default bridge. It is also off whenever `mock` is on.

Synthesised addresses come from the subnet's own CIDR block, skipping the first four addresses
and the last one as AWS reserves them, and skipping every address a persisted instance, network
interface or NAT gateway in the subnet still holds, so a restart does not hand one out twice. A
subnet with no free address left fails the request with `InsufficientFreeAddressesInSubnet`.

`RunInstances` with `PrivateIpAddress`, or `NetworkInterface.1.PrivateIpAddress` (the shape
Terraform's `aws_instance` sends for `private_ip` with a `subnet_id`), gives the instance and its
primary network interface that address instead of the next free one. So does the
`NetworkInterface.1.PrivateIpAddresses` entry marked `Primary`, the shape the AWS CLI sends for
`--private-ip-address` together with `--associate-public-ip-address` or the secondary-address
options, and the `PrivateIpAddress` of a launch template's primary interface. Secondary addresses,
from `PrivateIpAddresses` entries not marked `Primary` or from `SecondaryPrivateIpAddressCount`, are
not applied. An address outside the subnet's CIDR, or one of the five AWS reserves, fails with
`InvalidParameterValue`, and one that an instance, network interface, NAT gateway or interface
endpoint already holds fails with `InvalidIPAddress.InUse`. In a Docker-backed default subnet, whose
network every account shares, that includes an address another account's instance, network interface
or NAT gateway holds. As on AWS it applies only to a single instance that creates its own primary
interface: with more than one instance, or with `NetworkInterface.1.NetworkInterfaceId`, the call
fails with `InvalidParameterCombination`, and so does a top-level `PrivateIpAddress` sent with
network interfaces, from the request or the launch template. In a Docker-backed subnet the container
joins the VPC network at that address. In a subnet whose CIDR was substituted the address lies
outside the network's range, so the container cannot hold it, and the instance reports its bridge
address unless `FLOCI_SERVICES_EC2_AWS_FAITHFUL_PRIVATE_IP` is true, as for a synthesised address.

`CreateSubnet` derives `AvailableIpAddressCount` from the IPv4 CIDR, excluding the
five AWS-reserved addresses. `DescribeSubnets` subtracts addresses currently held
by instances, network interfaces, NAT gateways and interface endpoints. ASG
scale-out uses this count when selecting among subnets in the same zone.

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
- Security group rules are not enforced as a firewall (Docker bridge networking handles routing), but TCP ingress rules opened to a CIDR source are published on the host via socat sidecars so the instance's app is reachable from `localhost` — see [Security Group Port Publishing](#security-group-port-publishing).
- The IMDS server identifies which instance is calling via IMDSv2 tokens (mapped at token issuance time) or by the container's bridge IP for IMDSv1.

### External image catalog

Set `FLOCI_SERVICES_EC2_IMAGE_CATALOG_PATH` (`floci.services.ec2.image-catalog-path`)
to a readable YAML file to replace the bundled image catalog for a Floci process.
The file uses the same schema as `src/main/resources/ec2/image-catalog.yaml` and must
include every image that process should expose. Missing or invalid files fail on first
catalog use; leaving the setting unset preserves the bundled catalog. Containerized
Floci needs the file mounted at the configured container path.

### Public IPv4 addresses

Whether an instance has an automatic public IPv4 address is decided by the same rule with and
without containers. Only the address itself depends on the mode. With a container, it is the
container's reachable address: its own IP when Floci's clients can route to it, otherwise
`127.0.0.1` with the DNS name `localhost`. In mock mode there is no container to reach, so
`FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESSES` chooses:

- `none` (the default): no automatic address. DescribeInstances shows no `ipAddress` or `dnsName`
  unless an Elastic IP is associated. The launch still records whether it asked for one, so
  switching the setting takes effect at the instance's next start.
- `documentation`: an address from `203.0.113.0/24`, a range reserved for documentation that no
  host answers on, as Lightsail and EMR use. Instances get addresses distinct from each other and
  from the account's Elastic IPs while any are free (none once all are taken), and a restart gets
  a different one.
- `fixed`: `FLOCI_SERVICES_EC2_MOCK_PUBLIC_ADDRESS` (default `127.0.0.1`, DNS name `localhost`)
  for every instance, so a tool that connects to the public address reaches a host you choose. An
  Elastic IP associated with the primary private address shows that address too.

For an address that reaches the instance itself, run it with a container (`FLOCI_SERVICES_EC2_MOCK`
`false`). Elastic IPs come from `203.0.113.0/24` in both modes (see [Elastic IPs](#elastic-ips)).

- An instance applies `MapPublicIpOnLaunch` unless the primary launch interface explicitly sets
  `AssociatePublicIpAddress`. Direct launches, EC2 Fleet launches and Auto Scaling launch
  templates preserve both `true` and `false` overrides.
- As the
  [InstanceNetworkInterfaceSpecification reference](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_InstanceNetworkInterfaceSpecification.html)
  specifies, only an interface the launch creates gets an automatic address. An instance launched
  on an existing network interface has none.
- The address appears when the instance reaches `running` and remains stable while it runs. A
  stop or termination releases it, and a restart assigns a new one (the same one again under
  `fixed`); the private address does not change. As the
  [EC2 User Guide](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-instance-addressing.html#concepts-public-addresses)
  describes, a released address is not replaced while a second network interface is attached.
- An Elastic IP associated with the instance's primary private address takes the place of the
  automatic address and is kept across a stop. Disassociating it from a running instance assigns
  a new automatic address when the rule above allows one.
- An Elastic IP that a retained interface carries becomes the public address of the next instance
  launched on that interface, although DescribeAddresses does not report that instance as its
  `InstanceId`.

Under `none` the rule still runs, but a mock instance has only the Elastic IP, if any, to show.
Otherwise a mock instance's address appears whether DescribeInstances or DescribeInstanceStatus
observes the launch first. A terminated mock instance has no public address, and stopping it does not bring it
back.
