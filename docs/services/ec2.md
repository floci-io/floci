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
| RunInstances | Creates one or more local EC2 instances, starting Docker-backed runtime when not in mock mode. |
| CreateFleet | Creates an instant local fleet from launch-template configurations and on-demand or spot overrides. DryRun returns the AWS-compatible `DryRunOperation` error without launching instances. |
| DescribeInstances | Lists or returns stored EC2 instances. |
| TerminateInstances | Terminates instances and updates their stored lifecycle state. |
| StartInstances | Starts stopped instances and their local runtime when applicable. |
| StopInstances | Stops running instances and updates their stored lifecycle state. |
| RebootInstances | Reboots instances through the local EC2 service model. |
| DescribeInstanceStatus | Returns status records for stored instances. |
| DescribeInstanceAttribute | Returns a supported attribute for an instance. |
| ModifyInstanceAttribute | Updates supported mutable attributes for an instance. |

### VPCs

| Action | Description |
|--------|-------------|
| CreateVpc | Creates a VPC with the requested CIDR block. |
| DescribeVpcs | Lists or returns stored VPCs. |
| DeleteVpc | Deletes a VPC from the local EC2 store. |
| ModifyVpcAttribute | Updates supported VPC attributes. |
| DescribeVpcAttribute | Returns a supported VPC attribute. |
| DescribeVpcEndpointServices | Returns an empty local VPC endpoint service catalog. |
| CreateVpcEndpoint | Creates a VPC endpoint record, including its `PolicyDocument`. |
| DescribeVpcEndpoints | Lists or returns stored VPC endpoints. |
| ModifyVpcEndpoint | Associates or disassociates route tables, subnets and security groups, and sets or resets the endpoint policy. `DnsOptions`, `IpAddressType` and `SubnetConfiguration.N` are accepted and ignored. |
| DeleteVpcEndpoints | Deletes VPC endpoint records. |
| CreateDefaultVpc | Creates or returns the default VPC for the region. |
| AssociateVpcCidrBlock | Adds a secondary CIDR block association to a VPC. |
| DisassociateVpcCidrBlock | Removes a secondary CIDR block association from a VPC. |

### Subnets

| Action | Description |
|--------|-------------|
| CreateSubnet | Creates a subnet in a VPC. |
| DescribeSubnets | Lists or returns stored subnets. |
| DeleteSubnet | Deletes a subnet from the local EC2 store. |
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

| Action | Description |
|--------|-------------|
| CreateKeyPair | Creates and stores a local key pair. |
| DescribeKeyPairs | Lists or returns stored key pairs. |
| DeleteKeyPair | Deletes a key pair from the local EC2 store. |
| ImportKeyPair | Imports a public key as a local key pair. |

### AMIs

| Action | Description |
|--------|-------------|
| DescribeImages | Returns AMI metadata known to the local EC2 service. |
| CreateImage | Captures an instance as a new AMI. Reboots the source unless `NoReboot=true`. |
| RegisterImage | Registers an AMI from supplied metadata and block device mappings. |

### Tags

| Action | Description |
|--------|-------------|
| CreateTags | Adds tags to supported EC2 resources. |
| DeleteTags | Removes tags from supported EC2 resources. |
| DescribeTags | Lists tags stored for EC2 resources. |

### Internet Gateways

| Action | Description |
|--------|-------------|
| CreateInternetGateway | Creates an internet gateway. |
| DescribeInternetGateways | Lists or returns stored internet gateways. |
| DeleteInternetGateway | Deletes an internet gateway. |
| AttachInternetGateway | Attaches an internet gateway to a VPC. |
| DetachInternetGateway | Detaches an internet gateway from a VPC. |

### Route Tables

| Action | Description |
|--------|-------------|
| CreateRouteTable | Creates a route table in a VPC. |
| DescribeRouteTables | Lists or returns stored route tables. |
| DeleteRouteTable | Deletes a route table from the local EC2 store. |
| AssociateRouteTable | Associates a route table with a subnet. |
| DisassociateRouteTable | Removes a route table association. |
| CreateRoute | Adds a route to a route table. Accepts `VpcPeeringConnectionId` as a target (alongside `GatewayId`/`NatGatewayId`/`EgressOnlyInternetGatewayId`) and reports it back on `DescribeRouteTables`. |
| ReplaceRoute | Replaces the target of an existing route. |
| DeleteRoute | Removes a route from a route table. |

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
| CreateNetworkAcl | Creates a network ACL in a VPC. |
| DescribeNetworkAcls | Lists or returns stored network ACLs. |
| DeleteNetworkAcl | Deletes a network ACL from the local EC2 store. |
| CreateNetworkAclEntry | Adds an entry to a network ACL. |
| ReplaceNetworkAclEntry | Replaces an entry in a network ACL. |
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

### Elastic IPs

| Action | Description |
|--------|-------------|
| AllocateAddress | Allocates an Elastic IP address record. |
| DescribeAddresses | Lists or returns stored Elastic IP address records. |
| DescribeAddressesAttribute | Returns allocation ID and public IP attributes for Elastic IP addresses. |
| AssociateAddress | Associates an Elastic IP address with a resource. |
| DisassociateAddress | Removes an Elastic IP address association. |
| ReleaseAddress | Releases an Elastic IP address record. |

An allocated Elastic IP's `54.x.x.x` address is invented and routes nowhere, so associating
it re-points it at the address the instance is actually reachable on — the same value
DescribeInstances reports. This is a deliberate deviation from AWS, where an EIP's public IP
is fixed from allocation: without it, `aws_eip.x.public_ip` hands every caller a dead address.
The allocation ID, association ID and domain are unaffected, and disassociating restores the
allocated address.

### Availability Zones & Regions

| Action | Description |
|--------|-------------|
| DescribeAvailabilityZones | Returns the configured local availability zones. |
| DescribeRegions | Returns the regions known to the local EC2 service. |
| DescribeAccountAttributes | Returns local account-level EC2 attributes. |

### Instance Types

| Action | Description |
|--------|-------------|
| DescribeInstanceTypes | Returns instance type metadata known to the local EC2 service. |
| DescribeInstanceTypeOfferings | Returns instance type offerings for the requested location filters. |

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
`CapacityReservationSpecification`, `EbsOptimized`, `DisableApiTermination`, `DisableApiStop`,
`InstanceInitiatedShutdownBehavior`.

Two behaviours worth calling out, because they are what Terraform reads back:

- **`IamInstanceProfile` keeps the form it was given.** A profile submitted as `Name` reads back as
  `Name`, not rewritten to `Arn`. The instance-profile ARN is derived at launch time instead, so
  `aws_launch_template.iam_instance_profile.name` converges.
- **`NetworkInterfaces` stays a `NetworkInterfaces` block.** Its `Groups` are not hoisted into
  top-level `SecurityGroupIds`; on AWS the two are mutually exclusive. A launch from the template
  resolves its security groups from whichever of the two is populated.

#### Launch template versions

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
`InstanceMarketOptions`, `InstanceRequirements`, `LicenseSpecifications`, `ElasticGpuSpecifications`,
`ElasticInferenceAccelerators`, `NetworkPerformanceOptions`, `Operator`, `SecondaryInterfaces` and
`SecurityGroups` (security groups by name — resolving names to IDs would need lookup machinery,
including ambiguity handling across VPCs, that no other EC2 action here has either; `RunInstances`
itself only accepts `SecurityGroupId`). Within `NetworkInterfaces`, the IPv4/IPv6 address and
prefix lists, `EnaSrdSpecification`, `ConnectionTrackingSpecification`, `PrimaryIpv6` and
`EnaQueueCount` are likewise ignored.

### IAM Instance Profiles

| Action | Description |
|--------|-------------|
| DescribeIamInstanceProfileAssociations | Lists IAM instance profile associations known to the local EC2 service. |

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
| `FLOCI_SERVICES_EC2_AWS_FAITHFUL_PRIVATE_IP` | `false` | Report the CFN/subnet-allocated private IP instead of the container bridge IP; routing and IMDS are unaffected |
| `FLOCI_SERVICES_EC2_CONTAINER_IPS_ROUTABLE` | auto-detect | Whether an instance's container IP is reachable from the machines consuming Floci's API (Terraform, Terratest, your shell). When it is, DescribeInstances and DescribeAddresses report the container IP, so port 22 really is port 22; when it is not, they report `127.0.0.1` and reachability goes through the published high host ports. Detected by a throwaway TCP connect; set explicitly when Floci itself runs as a container |

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
