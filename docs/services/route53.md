# Route53

Route53 management-plane emulation. Supports hosted zones, resource record sets, health checks, change tracking, and tagging. Actual DNS resolution is not provided — this is a management-plane-only implementation.

## Supported Operations

| Operation | Method | Path |
|---|---|---|
| CreateHostedZone | POST | `/2013-04-01/hostedzone` |
| GetHostedZone | GET | `/2013-04-01/hostedzone/{Id}` |
| DeleteHostedZone | DELETE | `/2013-04-01/hostedzone/{Id}` |
| ListHostedZones | GET | `/2013-04-01/hostedzone` |
| ListHostedZonesByName | GET | `/2013-04-01/hostedzonesbyname` |
| GetHostedZoneCount | GET | `/2013-04-01/hostedzonecount` |
| ChangeResourceRecordSets | POST | `/2013-04-01/hostedzone/{Id}/rrset` |
| ListResourceRecordSets | GET | `/2013-04-01/hostedzone/{Id}/rrset` |
| GetChange | GET | `/2013-04-01/change/{Id}` |
| CreateHealthCheck | POST | `/2013-04-01/healthcheck` |
| GetHealthCheck | GET | `/2013-04-01/healthcheck/{HealthCheckId}` |
| DeleteHealthCheck | DELETE | `/2013-04-01/healthcheck/{HealthCheckId}` |
| ListHealthChecks | GET | `/2013-04-01/healthcheck` |
| UpdateHealthCheck | POST | `/2013-04-01/healthcheck/{HealthCheckId}` |
| ListTagsForResource | GET | `/2013-04-01/tags/{ResourceType}/{ResourceId}` |
| ChangeTagsForResource | POST | `/2013-04-01/tags/{ResourceType}/{ResourceId}` |
| GetAccountLimit | GET | `/2013-04-01/accountlimit/{Type}` |

## Behavior

- All changes return status `INSYNC` immediately (no async propagation simulation).
- Every new hosted zone automatically gets SOA and NS records at the zone apex. These records cannot be deleted.
- `DeleteHostedZone` fails with `HostedZoneNotEmpty` if the zone contains records other than the apex SOA and NS.
- `ChangeResourceRecordSets` validates all changes atomically before applying any.
- Supported change actions: `CREATE`, `UPSERT`, `DELETE`.
- Hosted zone IDs are returned with the `/hostedzone/` prefix in XML responses (e.g. `/hostedzone/Z1PA6795UKMFR9`). The AWS SDK strips this prefix client-side.
- Health check IDs are plain UUIDs without a prefix.
- Tags are supported for both `hostedzone` and `healthcheck` resource types.

## Default Nameservers

New zones use these nameservers (configurable via `floci.services.route53.*`):

```
ns-1.awsdns-01.org
ns-2.awsdns-02.net
ns-3.awsdns-03.com
ns-4.awsdns-04.co.uk
```

## Configuration

```yaml
floci:
  services:
    route53:
      enabled: true
      default-nameserver1: ns-1.awsdns-01.org
      default-nameserver2: ns-2.awsdns-02.net
      default-nameserver3: ns-3.awsdns-03.com
      default-nameserver4: ns-4.awsdns-04.co.uk
```

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

# Delete a hosted zone
aws route53 delete-hosted-zone --id Z1PA6795UKMFR9
```

## Not Supported (Phase 2)

- Reusable delegation sets
- Traffic policies and traffic policy instances
- VPC association (private hosted zones)
- Query logging configs
- DNSSEC (key signing keys, enabling/disabling)
- `TestDNSAnswer`
- Actual DNS resolution
