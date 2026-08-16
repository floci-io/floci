# Route 53 Resolver

## Supported operations

| Family | Operations |
|---|---|
| DNS Firewall domain lists (AWS-managed) | `ListFirewallDomainLists`, `GetFirewallDomainList` |
| DNS Firewall domain lists (custom) | `CreateFirewallDomainList`, `DeleteFirewallDomainList` |
| Resolver endpoints | `CreateResolverEndpoint`, `GetResolverEndpoint`, `ListResolverEndpoints`, `UpdateResolverEndpoint`, `DeleteResolverEndpoint` |
| Resolver rules | `CreateResolverRule`, `GetResolverRule`, `ListResolverRules`, `UpdateResolverRule`, `DeleteResolverRule` |
| Resolver rule associations | `AssociateResolverRule`, `DisassociateResolverRule`, `GetResolverRuleAssociation`, `ListResolverRuleAssociations` |

## Design notes

The four AWS-managed DNS Firewall domain lists (`AWSManagedDomainsAggregateThreatList`
and friends) are computed on every call from a fixed name list, with ids derived
deterministically from region+name (SHA-256 based) rather than stored — LZA's
`Custom::ResolverManagedDomainList` Lambda resolves a managed list's Id by Name and
needs it present and stable without any create call. This logic predates the rest of
this service and is untouched.

Everything else (custom firewall domain lists, resolver endpoints, resolver rules,
rule associations) is backed by real per-service storage, added this session. Ids use
the project's standard random-suffix convention (`rslvr-fdl-...`, `rslvr-in-...`,
`rslvr-rr-...`, `rslvr-rrassoc-...`), distinct from the deterministic managed-list ids.

## Limitations

- **All create/update operations complete synchronously.** Real AWS transitions a
  resolver endpoint through `CREATING`, a resolver rule through its own provisioning
  states, before reaching a terminal status. This emulator returns the terminal state
  (`OPERATIONAL` for endpoints, `COMPLETE` for rules and associations) immediately —
  matching the "validate-and-echo" convention used elsewhere in this codebase (see
  `ServiceCatalogService.copyProduct`, `CS-021`).
- **`UpdateResolverEndpoint` only applies `Name` and `ResolverEndpointType`.** Real AWS
  additionally allows updating `IpAddresses` (adding/removing resolver IPs); that is not
  modelled.
- **`CreateResolverRule`/`UpdateResolverRule` do not validate `ResolverEndpointId`
  against an existing endpoint.** Any non-blank string is accepted.
- **No VPC-scoping or region-scoping is enforced anywhere in this service.** All
  resources are visible regardless of the VPC or account context of the caller.
