# Route 53 Resolver

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListFirewallDomainLists` | Lists all DNS Firewall domain lists, including the four AWS-managed lists computed per region. |
| `GetFirewallDomainList` | Returns a single DNS Firewall domain list by id, whether AWS-managed or custom. |
| `CreateFirewallDomainList` | Creates a custom DNS Firewall domain list with a random-suffix `rslvr-fdl-` id. |
| `DeleteFirewallDomainList` | Deletes a custom DNS Firewall domain list; AWS-managed lists cannot be deleted. |
| `CreateResolverEndpoint` | Creates an inbound or outbound resolver endpoint, returned immediately as `OPERATIONAL`. |
| `DeleteResolverEndpoint` | Deletes a resolver endpoint and returns its final description. |
| `GetResolverEndpoint` | Returns a resolver endpoint by id. |
| `ListResolverEndpoints` | Lists all resolver endpoints. |
| `UpdateResolverEndpoint` | Updates a resolver endpoint's `Name` and `ResolverEndpointType`; `IpAddresses` changes are not modelled. |
| `CreateResolverRule` | Creates a resolver rule, returned immediately as `COMPLETE`; `ResolverEndpointId` is not validated against an existing endpoint. |
| `DeleteResolverRule` | Deletes a resolver rule and returns its final description. |
| `GetResolverRule` | Returns a resolver rule by id. |
| `ListResolverRules` | Lists all resolver rules. |
| `UpdateResolverRule` | Updates a resolver rule's mutable configuration. |
| `AssociateResolverRule` | Associates a resolver rule with a VPC, returned immediately as `COMPLETE`. |
| `DisassociateResolverRule` | Removes the association between a resolver rule and a VPC. |
| `GetResolverRuleAssociation` | Returns a resolver rule association by id. |
| `ListResolverRuleAssociations` | Lists all resolver rule associations. |
<!-- floci:actions:end -->

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

`CreateFirewallDomainList`, `CreateResolverEndpoint` and `CreateResolverRule` are
idempotent on `CreatorRequestId`, as they are in real AWS: replaying a token returns the
resource it originally created instead of allocating a second one. The check runs after
the request's own validation, so a replayed token never excuses a malformed body. A
request without a `CreatorRequestId` opts out and always allocates a new resource.

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
