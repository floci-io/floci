# Route53

Route53 management-plane emulation. Supports hosted zones, resource record sets, health checks, change tracking, and tagging. Actual DNS resolution is not provided — this is a management-plane-only implementation.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateHostedZone` | `POST /2013-04-01/hostedzone`. A `<VPC>` element in the request creates a private zone and records the first VPC association. |
| `GetHostedZone` | `GET /2013-04-01/hostedzone/{Id}`. Private zones include their `<VPCs>` list. |
| `DeleteHostedZone` | `DELETE /2013-04-01/hostedzone/{Id}` |
| `ListHostedZones` | `GET /2013-04-01/hostedzone` |
| `ListHostedZonesByName` | `GET /2013-04-01/hostedzonesbyname` |
| `AssociateVPCWithHostedZone` | `POST /2013-04-01/hostedzone/{Id}/associatevpc`. Private zones only; re-associating an attached VPC is a no-op. |
| `DisassociateVPCFromHostedZone` | `POST /2013-04-01/hostedzone/{Id}/disassociatevpc`. Refuses to remove the last association (`LastVPCAssociation`). |
| `CreateVPCAssociationAuthorization` | `POST /2013-04-01/hostedzone/{Id}/authorizevpcassociation`. Validates and echoes the VPC; zones are not account-scoped, so no authorization is enforced. |
| `DeleteVPCAssociationAuthorization` | `POST /2013-04-01/hostedzone/{Id}/deauthorizevpcassociation` |
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
- Hosted zone IDs are returned with the `/hostedzone/` prefix in XML responses (e.g. `/hostedzone/Z1PA6795UKMFR9`). The AWS SDK strips this prefix client-side.
- Health check IDs are plain UUIDs without a prefix.
- Tags are supported for both `hostedzone` and `healthcheck` resource types.
- A hosted zone is private when `CreateHostedZone` carries a `<VPC>` element; `HostedZoneConfig.PrivateZone` is response-only.
- `AssociateVPCWithHostedZone` rejects public zones with `PublicZoneVPCAssociation`, and `DisassociateVPCFromHostedZone` rejects removing the last VPC with `LastVPCAssociation`.
- Associating a VPC that is already attached to another zone with the same name fails with `ConflictingDomainExists`.

## Default Nameservers

New zones use these nameservers (configurable via `floci.services.route53.*`):

```
ns-1.awsdns-01.org
ns-2.awsdns-02.net
ns-3.awsdns-03.com
ns-4.awsdns-04.co.uk
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ROUTE53_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER1` | `ns-1.awsdns-01.org` | First default nameserver returned in delegation sets |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER2` | `ns-2.awsdns-02.net` | Second default nameserver |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER3` | `ns-3.awsdns-03.com` | Third default nameserver |
| `FLOCI_SERVICES_ROUTE53_DEFAULT_NAMESERVER4` | `ns-4.awsdns-04.co.uk` | Fourth default nameserver |

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

## Not Supported (Phase 2)

- Reusable delegation sets
- Traffic policies and traffic policy instances
- Cross-account VPC association authorization (zones are not account-scoped, so `CreateVPCAssociationAuthorization` is accepted but not enforced)
- Query logging configs
- DNSSEC (key signing keys, enabling/disabling)
- `TestDNSAnswer`
- Actual DNS resolution
