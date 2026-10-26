# Route53

Route53 emulation supporting hosted zones, resource record sets, health checks, change tracking, and tagging. Private hosted zones provide embedded DNS resolution for cluster containers such as EKS and workloads wired to Floci's embedded DNS server.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateHostedZone` | `POST /2013-04-01/hostedzone`. A `<VPC>` element in the request creates a private zone and records the first VPC association. |
| `GetHostedZone` | `GET /2013-04-01/hostedzone/{Id}`. Private zones include their `<VPCs>` list. |
| `UpdateHostedZoneComment` | `POST /2013-04-01/hostedzone/{Id}` |
| `DeleteHostedZone` | `DELETE /2013-04-01/hostedzone/{Id}` |
| `ListHostedZones` | `GET /2013-04-01/hostedzone` |
| `ListHostedZonesByName` | `GET /2013-04-01/hostedzonesbyname` |
| `AssociateVPCWithHostedZone` | `POST /2013-04-01/hostedzone/{Id}/associatevpc`. Private zones only; cross-account associations require prior authorization. |
| `DisassociateVPCFromHostedZone` | `POST /2013-04-01/hostedzone/{Id}/disassociatevpc`. Refuses to remove the last association (`LastVPCAssociation`). |
| `CreateVPCAssociationAuthorization` | `POST /2013-04-01/hostedzone/{Id}/authorizevpcassociation`. Must be called by the hosted-zone owner. |
| `DeleteVPCAssociationAuthorization` | `POST /2013-04-01/hostedzone/{Id}/deauthorizevpcassociation`. Must be called by the hosted-zone owner; success has an empty response body. |
| `ListVPCAssociationAuthorizations` | `GET /2013-04-01/hostedzone/{Id}/authorizevpcassociation`. Paginates with `maxresults` / `nexttoken`. |
| `ListHostedZonesByVPC` | `GET /2013-04-01/hostedzonesbyvpc?vpcid=…&vpcregion=…`. Paginates with `maxitems` / `nexttoken`. |
| `GetHostedZoneCount` | `GET /2013-04-01/hostedzonecount` |
| `ChangeResourceRecordSets` | `POST /2013-04-01/hostedzone/{Id}/rrset` |
| `ListResourceRecordSets` | `GET /2013-04-01/hostedzone/{Id}/rrset` |
| `GetChange` | `GET /2013-04-01/change/{Id}` |
| `CreateHealthCheck` | `POST /2013-04-01/healthcheck` |
| `GetHealthCheck` | `GET /2013-04-01/healthcheck/{HealthCheckId}` |
| `DeleteHealthCheck` | `DELETE /2013-04-01/healthcheck/{HealthCheckId}` |
| `ListHealthChecks` | `GET /2013-04-01/healthcheck` |
| `UpdateHealthCheck` | `POST /2013-04-01/healthcheck/{HealthCheckId}` |
| `ListTagsForResource` | `GET /2013-04-01/tags/{ResourceType}/{ResourceId}` |
| `ChangeTagsForResource` | `POST /2013-04-01/tags/{ResourceType}/{ResourceId}` |
| `GetAccountLimit` | `GET /2013-04-01/accountlimit/{Type}` |
| `GetHealthCheckStatus` | `GET /2013-04-01/healthcheck/{HealthCheckId}/status` |
| `GetDNSSEC` | `GET /2013-04-01/hostedzone/{Id}/dnssec`. Always reports signing as `NOT_SIGNING`. |
| `GetHostedZoneLimit` | `GET /2013-04-01/hostedzonelimit/{HostedZoneId}/{Type}` |
<!-- floci:actions:end -->

## Behavior

- All changes return status `INSYNC` immediately (no async propagation simulation).
- Every new hosted zone automatically gets SOA and NS records at the zone apex. These records cannot be deleted.
- `DeleteHostedZone` fails with `HostedZoneNotEmpty` if the zone contains records other than the apex SOA and NS.
- `ChangeResourceRecordSets` validates all changes atomically before applying any.
- Supported change actions: `CREATE`, `UPSERT`, `DELETE`.
- Alias deletion treats target DNS names without regard to ASCII letter case and accepts an optional trailing dot. The target hosted-zone ID, health-evaluation setting, and other record values must still match; a rejected change batch does not apply its other changes.
- Hosted zone IDs are returned with the `/hostedzone/` prefix in XML responses (e.g. `/hostedzone/Z1PA6795UKMFR9`). The AWS SDK strips this prefix client-side.
- Health check IDs are plain UUIDs without a prefix.
- `CreateHealthCheck` with a `CallerReference` already in use returns that health check when the settings match, so an SDK retry succeeds, and fails with `HealthCheckAlreadyExists` when they differ. A deleted health check's reference can be reused at once; AWS keeps refusing it for some days.
- Tags are supported for both `hostedzone` and `healthcheck` resource types.
- A hosted zone is private when `CreateHostedZone` carries a `<VPC>` element; `HostedZoneConfig.PrivateZone` is response-only.
- `AssociateVPCWithHostedZone` rejects public zones with `PublicZoneVPCAssociation`, and `DisassociateVPCFromHostedZone` rejects removing the last VPC with `LastVPCAssociation`.
- Associating a VPC that is already attached to another zone with the same name fails with `ConflictingDomainExists`.
- Cross-account association follows the Route 53 authorization lifecycle: the hosted-zone owner authorizes the VPC, the VPC account associates it, and the hosted-zone owner can then delete the authorization without removing the association.
- A cross-account association without a matching authorization fails with `NotAuthorizedException`.
- Private hosted zones support up to 300 VPC associations and up to 1000 outstanding cross-account VPC association authorizations, matching the documented Route 53 default quotas.
- Set `FLOCI_SERVICES_ROUTE53_VPC_ASSOCIATION_CONTROL_PLANE_DELAY_MS` above `0` to emulate a short in-flight control-plane window for retry testing. During that window, a subsequent `AssociateVPCWithHostedZone` for the same hosted zone returns `PriorRequestNotComplete`; overlapping create/delete authorization requests return `ConcurrentModification`. These are the retryable overlap errors documented by Route 53 for those operations.

## Private Hosted Zone DNS Resolution

Floci resolves private hosted zone DNS records directly through its embedded DNS server on port 53. Cluster containers (such as EKS k3s containers) and workloads wired to embedded DNS automatically query Floci as their primary resolver.

- **Record Types**: Resolves IPv4 `A` records (including `CNAME` and `ALIAS` target chasing across private zones, EC2 internal names, and external hostnames). When a private CNAME targets a public hostname, Floci resolves the target addresses off the event loop so container stub resolvers succeed. Multiple values are returned as separate answers.
- **TTL and Negative Answers**: Answers carry the record set's `TTL`. A chased `CNAME` chain is flattened to `A` records, so it answers with the shortest TTL in the chain; an alias answers with its target's TTL, and an alias or `CNAME` to a name outside the private zones answers with at most 60 seconds, since Floci cannot see that target's TTL. A name absent from the zone receives NXDOMAIN; a name that exists without an `A` record (only `TXT`, for example, or only records beneath it) receives NOERROR with no answers. A wildcard record such as `*.example.internal` answers only when neither the name nor an ancestor closer than the wildcard exists, so a record at `api.team.example.internal` keeps the wildcard from answering for `team.example.internal` or anything beneath it.
- **Zone Visibility and Fallthrough**: Private hosted zones (created with a VPC association) retain IPv4 resolution. Public zones support the authoritative record types described below; unowned external domains fall through to upstream forwarders. Because the standard DNS wire protocol carries no caller VPC identifier, Floci resolves across private zones without filtering by account or VPC ID.
- **Resolver Rule Precedence**: A Route 53 Resolver rule associated with the querying container's VPC takes precedence over a private hosted zone that matches the same name, as it does in AWS: the query is forwarded to the rule's targets instead of answered from the zone. See [Route 53 Resolver](route53resolver.md#resolver-rules-and-dns-resolution).
- **Zero-Restart Propagation**: Record changes via `ChangeResourceRecordSets` take effect immediately without restarts.
- **Kubernetes and external-dns Compatibility**: In-cluster controllers like `external-dns` provisioning Route 53 private records for Services or Ingresses are immediately resolvable.

## Public Authoritative DNS

The same embedded UDP server serves stored public `TXT`, `NS`, and `SOA` records with authoritative answers and their configured TTLs. DNS clients must be able to reach Floci on UDP port 53. The HTTP endpoint published by Testcontainers wrappers does not publish this UDP listener. Host clients need an explicit UDP port mapping; containers on the same network can query Floci's container address directly.

For a Docker host client, publish UDP separately from HTTP, for example `-p 5353:53/udp`, then query `dig @127.0.0.1 -p 5353 example.com TXT`. Configure the equivalent UDP binding explicitly when using a Testcontainers wrapper; its standard HTTP port mapping is insufficient.

The longest matching public zone answers. Missing names return NXDOMAIN; existing names without the requested supported type return NOERROR with no answers. Both include the zone SOA with the smaller of its TTL and minimum field for negative caching. Changes and deletes affect DNS through the same stored record sets as the Route 53 API.

Public address routing, aliases, CNAME chasing, health-based or weighted record selection, and delegation referrals are not implemented. Queries for supported types that require these features, ambiguous duplicate public zones, or malformed stored records return REFUSED without claiming authority. Other query types retain the existing emulator-name and upstream resolution paths. Private-zone and VPC forwarding-rule precedence remains unchanged. UDP responses honor legacy/EDNS size limits and set truncation when necessary; TCP fallback is not implemented.

## Default Nameservers

New zones use these nameservers (configurable via `floci.services.route53.*`):

```
ns-1.awsdns-01.org
ns-2.awsdns-02.net
ns-3.awsdns-03.com
ns-4.awsdns-04.co.uk
```

A configured name can be written with or without the final dot. The delegation set lists it without the dot, as Route 53 does. A new zone's apex NS records, and the primary server named in its SOA record (the first nameserver), use the fully qualified form with the dot.

The apex NS and SOA records are written when a zone is created, while the delegation set that `CreateHostedZone` and `GetHostedZone` return always reflects the current settings. A zone kept in persistent storage therefore keeps its original NS and SOA records after these settings change; update them with an `UPSERT` change.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ROUTE53_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER1` | `ns-1.awsdns-01.org` | First default nameserver returned in delegation sets |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER2` | `ns-2.awsdns-02.net` | Second default nameserver |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER3` | `ns-3.awsdns-03.com` | Third default nameserver |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER4` | `ns-4.awsdns-04.co.uk` | Fourth default nameserver |
| `FLOCI_SERVICES_ROUTE53_VPC_ASSOCIATION_CONTROL_PLANE_DELAY_MS` | `0` | Optional VPC association/auth processing window used to reproduce documented retryable overlap errors |

## CLI Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Create a hosted zone
aws route53 create-hosted-zone \
  --name example.com \
  --caller-reference "$(date +%s)"

# List hosted zones
aws route53 list-hosted-zones

# Add an A record
aws route53 change-resource-record-sets \
  --hosted-zone-id Z1PA6795UKMFR9 \
  --change-batch '{
    "Changes": [{
      "Action": "CREATE",
      "ResourceRecordSet": {
        "Name": "www.example.com.",
        "Type": "A",
        "TTL": 300,
        "ResourceRecords": [{"Value": "1.2.3.4"}]
      }
    }]
  }'

# List records
aws route53 list-resource-record-sets --hosted-zone-id Z1PA6795UKMFR9

# Create a health check
aws route53 create-health-check \
  --caller-reference "hc-$(date +%s)" \
  --health-check-config '{
    "Type": "HTTPS",
    "FullyQualifiedDomainName": "example.com",
    "Port": 443,
    "ResourcePath": "/health"
  }'

# Create a private hosted zone attached to a VPC
aws route53 create-hosted-zone \
  --name internal.example.com \
  --caller-reference "$(date +%s)" \
  --vpc VPCRegion=us-east-1,VPCId=vpc-0123456789abcdef0

# Attach a second VPC to the private zone
aws route53 associate-vpc-with-hosted-zone \
  --hosted-zone-id Z1PA6795UKMFR9 \
  --vpc VPCRegion=us-east-1,VPCId=vpc-0fedcba9876543210

# List the private zones attached to a VPC
aws route53 list-hosted-zones-by-vpc \
  --vpc-id vpc-0fedcba9876543210 --vpc-region us-east-1

# Detach a VPC (the last association cannot be removed)
aws route53 disassociate-vpc-from-hosted-zone \
  --hosted-zone-id Z1PA6795UKMFR9 \
  --vpc VPCRegion=us-east-1,VPCId=vpc-0fedcba9876543210

# Delete a hosted zone
aws route53 delete-hosted-zone --id Z1PA6795UKMFR9
```

Record names in change batches are stored with lowercase ASCII letters and a trailing dot.
CREATE, UPSERT, and DELETE use that name identity while keeping the record type and
routing set identifier distinct. A case-only CloudFormation record-name update retains
the record. DELETE still requires matching record values and routing properties.

## Not Supported (Phase 2)

- Reusable delegation sets
- Traffic policies and traffic policy instances
- Query logging configs
- DNSSEC (key signing keys, enabling/disabling)
- `TestDNSAnswer`
- Public authoritative record types other than simple TXT, NS, and SOA; public routing policies, delegation referrals, and DNS-over-TCP
